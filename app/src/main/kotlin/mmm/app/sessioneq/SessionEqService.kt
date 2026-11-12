package mmm.app.sessioneq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.data.PlaybackEq
import mmm.eq.EqProfile
import mmm.eq.GraphicEqBands
import java.util.concurrent.Executors

/** One app's audio session the EQ is attached to. */
data class AttachedSession(
    val sessionId: Int,
    val packageName: String,
    val bandCount: Int,
    val error: String? = null,
)

data class SessionEqStatus(
    val running: Boolean = false,
    val sessions: List<AttachedSession> = emptyList(),
    /** Attached to the global output (session 0) instead of waiting for apps to announce theirs. */
    val global: Boolean = false,
    val message: String? = null,
)

/**
 * Puts the playback EQ on other apps' audio - Spotify's - without capturing it.
 *
 * Music apps announce each playback session with `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`, the
 * broadcast system equalisers listen for. This service listens for it too and attaches a
 * DynamicsProcessing effect to the announced session, with the PEQ's curve laid out as a dense
 * band equaliser (see [GraphicEqBands]). Nothing is captured or replayed, so there is no second
 * copy of the music and any output works - Bluetooth included.
 *
 * Whether an app announces its session is up to the app. For those that do not, the global mode
 * attaches to session 0, the deprecated output-mix hook that some devices still honour.
 *
 * Android offers other apps' audio only this band equaliser, never filters, so the curve is
 * always converted; the grid is as fine as the device accepts, starting from the listener's
 * setting and stepping down until an effect can be made.
 */
class SessionEqService : Service() {

    // Every effect call goes through one thread: a profile laid out as hundreds of bands is
    // hundreds of binder calls, too many for the main thread while a slider is being dragged.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val effects = HashMap<Int, DynamicsProcessing>()
    private val grids = HashMap<Int, Int>()
    private val packages = HashMap<Int, String>()
    private val failed = HashMap<Int, String>()
    private var profile: EqProfile? = null
    private var bandsPerOctave = PlaybackEq.SYSTEM_BANDS.first()
    private var visualizer: Visualizer? = null
    private var visualizerSession: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (session == AudioEffect.ERROR_BAD_VALUE || session == 0) return
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
            if (pkg == packageName) return
            val open = intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
            scope.launch { if (open) attach(session, pkg) else detach(session) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Exported: the announcements come from other apps.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        _status.value = SessionEqStatus(running = true)

        val app = application as MmmApplication
        scope.launch {
            app.eq.state.map { it.systemGlobal to it.systemBandsPerOctave }.distinctUntilChanged().collect { (global, perOctave) ->
                if (perOctave != bandsPerOctave) {
                    // A different grid is a different effect: the band count is fixed when it is made.
                    bandsPerOctave = perOctave
                    val attached = effects.keys.map { it to (packages[it] ?: "?") }
                    attached.forEach { (session, _) -> detach(session) }
                    attached.forEach { (session, pkg) -> attach(session, pkg) }
                }
                if (global) attach(GLOBAL_SESSION, "전역 출력") else detach(GLOBAL_SESSION)
            }
        }
        scope.launch {
            app.activeCorrection.collectLatest { eq ->
                // A dragged slider sends a profile a frame; only the one it rests on is written.
                delay(SETTLE_MS)
                profile = eq
                effects.keys.toList().forEach(::apply)
                publish()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        // The worker finishes what it is doing, then lets go of everything.
        worker.executor.execute {
            effects.keys.toList().forEach(::detach)
            visualizer?.release()
            visualizer = null
            latestVisualizer = null
            _status.value = SessionEqStatus(running = false)
        }
        worker.close()
        super.onDestroy()
    }

    private fun attach(session: Int, pkg: String) {
        if (effects.containsKey(session)) return
        packages[session] = pkg
        failed.remove(session)
        var lastError: Throwable? = null
        // Not every device takes the full band count; fall back to coarser grids.
        for (perOctave in PlaybackEq.SYSTEM_BANDS.filter { it <= bandsPerOctave }) {
            val bands = GraphicEqBands.from(profile ?: EqProfile.FLAT, perOctave)
            val effect = runCatching { create(session, bands, profile != null) }.getOrElse {
                lastError = it
                null
            } ?: continue
            effects[session] = effect
            grids[session] = perOctave
            apply(session)
            if (failed.containsKey(session)) {
                // Made, but would not take the bands: try the next grid down.
                effects.remove(session)
                grids.remove(session)
                runCatching { effect.release() }
                lastError = IllegalStateException(failed.remove(session))
                continue
            }
            attachVisualizer(session)
            publish()
            return
        }
        failed[session] = "효과를 붙이지 못했다: ${lastError?.message ?: lastError?.javaClass?.simpleName}"
        publish()
    }

    /**
     * Makes the effect with the bands and input gain already in its configuration, and only then
     * switches it on. Made empty and filled afterwards, it would play a moment of the app's audio
     * at unity gain before the EQ's preamp arrived - a surge at the start of every song.
     */
    private fun create(session: Int, bands: GraphicEqBands, active: Boolean): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bands.bands.size,
            false, 0,
            false, 0,
            false,
        ).build()
        val eq = DynamicsProcessing.Eq(true, active, bands.bands.size)
        bands.bands.forEachIndexed { i, band ->
            eq.setBand(i, DynamicsProcessing.EqBand(true, band.cutoffHz.toFloat(), band.gainDb.toFloat()))
        }
        config.setPreEqAllChannelsTo(eq)
        config.setInputGainAllChannelsTo(if (active) bands.preampDb.toFloat() else 0f)
        return DynamicsProcessing(PRIORITY, session, config).apply { setEnabled(true) }
    }

    /** Writes the current profile into [session]'s effect, on the grid it was created with. */
    private fun apply(session: Int) {
        val effect = effects[session] ?: return
        val perOctave = grids[session] ?: return
        val current = profile
        val bands = GraphicEqBands.from(current ?: EqProfile.FLAT, perOctave)
        runCatching {
            val eq = DynamicsProcessing.Eq(true, current != null, bands.bands.size)
            bands.bands.forEachIndexed { i, band ->
                eq.setBand(i, DynamicsProcessing.EqBand(true, band.cutoffHz.toFloat(), band.gainDb.toFloat()))
            }
            effect.setPreEqAllChannelsTo(eq)
            effect.setInputGainAllChannelsTo(if (current != null) bands.preampDb.toFloat() else 0f)
        }.onFailure { failed[session] = "EQ를 쓰지 못했다: ${it.message}" }
    }

    private fun detach(session: Int) {
        effects.remove(session)?.let { runCatching { it.setEnabled(false); it.release() } }
        grids.remove(session)
        packages.remove(session)
        failed.remove(session)
        if (visualizerSession == session) {
            visualizer?.release()
            visualizer = null
            latestVisualizer = null
            visualizerSession = null
        }
        publish()
    }

    /** The spectrum display reads the most recently attached session's audio. */
    private fun attachVisualizer(session: Int) {
        visualizer?.release()
        visualizer = runCatching {
            Visualizer(session).apply {
                setCaptureSize(Visualizer.getCaptureSizeRange()[1])
                setEnabled(true)
            }
        }.getOrNull()
        visualizerSession = if (visualizer != null) session else null
        latestVisualizer = visualizer
    }

    private fun publish() {
        val all = (effects.keys + failed.keys).toSortedSet()
        _status.value = SessionEqStatus(
            running = true,
            sessions = all.map { id ->
                AttachedSession(
                    sessionId = id,
                    packageName = packages[id] ?: "?",
                    bandCount = effects[id]?.let { runCatching { it.config.getPreEqByChannelIndex(0).bandCount }.getOrDefault(0) } ?: 0,
                    error = failed[id],
                )
            },
            global = effects.containsKey(GLOBAL_SESSION),
        )
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "다른 앱 EQ", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("이어폰 EQ")
            .setContentText("Spotify 등 다른 앱 소리에 EQ를 거는 중")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mmm_session_eq"
        private const val NOTIFICATION_ID = 0x4551
        private const val CHANNELS = 2
        private const val PRIORITY = 1000
        private const val GLOBAL_SESSION = 0
        private const val SETTLE_MS = 80L

        const val ACTION_STOP = "mmm.sessioneq.STOP"

        private val _status = MutableStateFlow(SessionEqStatus())
        val status: StateFlow<SessionEqStatus> = _status.asStateFlow()

        /** For the spectrum display; null when no session is attached. */
        @Volatile
        var latestVisualizer: Visualizer? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionEqService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionEqService::class.java))
        }
    }
}
