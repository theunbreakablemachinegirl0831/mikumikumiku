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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.eq.EqProfile
import mmm.eq.GraphicEqBands

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
 * Puts the earphone EQ on other apps' audio - Spotify's - without capturing it.
 *
 * Music apps announce each playback session with `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`, the
 * broadcast system equalisers listen for. This service listens for it too and attaches a
 * DynamicsProcessing effect to the announced session, with the PEQ's curve laid out as a dense
 * band equaliser (see [GraphicEqBands]). Nothing is captured or replayed, so there is no second
 * copy of the music and any output works - Bluetooth included.
 *
 * Whether an app announces its session is up to the app. For those that do not, the global mode
 * attaches to session 0, the deprecated output-mix hook that some devices still honour.
 */
class SessionEqService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val effects = HashMap<Int, DynamicsProcessing>()
    private val packages = HashMap<Int, String>()
    private var profile: EqProfile? = null
    private var visualizer: Visualizer? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (session == AudioEffect.ERROR_BAD_VALUE || session == 0) return
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
            if (pkg == packageName) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session, pkg)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(session)
            }
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
            app.activeCorrection.collect { eq ->
                profile = eq
                effects.keys.toList().forEach(::apply)
                publish()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GLOBAL_ON -> attach(0, "전역 출력")
            ACTION_GLOBAL_OFF -> detach(0)
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        effects.keys.toList().forEach(::detach)
        visualizer?.release()
        visualizer = null
        scope.cancel()
        _status.value = SessionEqStatus(running = false)
        latestVisualizer = null
        super.onDestroy()
    }

    private fun attach(session: Int, pkg: String) {
        if (effects.containsKey(session)) return
        packages[session] = pkg
        var lastError: Throwable? = null
        // Not every device takes the full band count; fall back to coarser grids.
        for (perOctave in BANDS_PER_OCTAVE_TRIES) {
            val bands = GraphicEqBands.from(profile ?: EqProfile.FLAT, perOctave)
            val effect = runCatching { create(session, bands.bands.size) }.getOrElse {
                lastError = it
                null
            } ?: continue
            effects[session] = effect
            apply(session)
            attachVisualizer(session)
            publish()
            return
        }
        failed[session] = "효과를 붙이지 못했다: ${lastError?.message ?: lastError?.javaClass?.simpleName}"
        publish()
    }

    private fun create(session: Int, bandCount: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bandCount,
            false, 0,
            false, 0,
            false,
        ).build()
        return DynamicsProcessing(PRIORITY, session, config).apply { setEnabled(true) }
    }

    /** Writes the current profile into [session]'s effect, at the band count it was created with. */
    private fun apply(session: Int) {
        val effect = effects[session] ?: return
        val bandCount = effect.config.getPreEqByChannelIndex(0).bandCount
        val perOctave = BANDS_PER_OCTAVE_TRIES.firstOrNull { GraphicEqBands.from(EqProfile.FLAT, it).bands.size == bandCount }
            ?: BANDS_PER_OCTAVE_TRIES.last()
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

    private var visualizerSession: Int? = null

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

    private val failed = HashMap<Int, String>()

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
            global = effects.containsKey(0),
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
        private val BANDS_PER_OCTAVE_TRIES = listOf(24, 12, 6)

        const val ACTION_GLOBAL_ON = "mmm.sessioneq.GLOBAL_ON"
        const val ACTION_GLOBAL_OFF = "mmm.sessioneq.GLOBAL_OFF"
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

        fun command(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, SessionEqService::class.java).setAction(action))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionEqService::class.java))
        }
    }
}
