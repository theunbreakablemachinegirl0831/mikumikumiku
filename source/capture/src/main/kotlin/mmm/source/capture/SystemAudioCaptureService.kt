package mmm.source.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mmm.audio.AudioOutput
import mmm.audio.OutputRoute
import mmm.audio.PlaybackEngine
import mmm.dsp.stream.RingBufferSource
import mmm.dsp.AudioBuffer
import mmm.dsp.AudioProcessor
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing and runs it through the trainer's artifact chain.
 *
 * This is the same mechanism UAPP's "audio from other apps" feature pack uses: Android 10's
 * `AudioPlaybackCapture`, driven by a MediaProjection grant. Apps opt out of being captured, and
 * when they do the API does not fail - it hands over a stream of zeroes - so the diagnostics path
 * around this service exists to tell "blocked" apart from "broken".
 */
public class SystemAudioCaptureService : Service() {

    public inner class LocalBinder : Binder() {
        public val service: SystemAudioCaptureService get() = this@SystemAudioCaptureService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    public val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    public val telemetry: StateFlow<CaptureTelemetry> = _telemetry.asStateFlow()

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var ring: RingBufferSource? = null
    private var engine: PlaybackEngine? = null
    private var captureThread: Thread? = null
    private var telemetryThread: Thread? = null

    @Volatile private var capturing = false
    private var startedAtMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked the grant from the system UI, or another app took the projection.
            stopCapture("화면/오디오 캡처 권한이 해제되었다")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Order matters on Android 14+: the service has to already be foreground with the
        // mediaProjection type before getMediaProjection() is called, or the system throws.
        promoteToForeground()

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val route = OutputRoute.fromId(intent.getStringExtra(EXTRA_ROUTE))
            if (data == null) {
                _state.value = CaptureState.Failed("캡처 권한 결과가 비어 있다")
            } else {
                startCapture(resultCode, data, route)
            }
        } else if (intent?.action == ACTION_STOP) {
            stopCapture(null)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- capture

    private fun startCapture(resultCode: Int, data: Intent, route: OutputRoute) {
        if (capturing) return
        _state.value = CaptureState.Starting

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Belt and braces against capturing ourselves: excludeUid below is the real guard, but if a
        // patched ROM ignores it this stops the feedback loop turning into an infinite echo.
        audioManager.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_NONE

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .getOrElse {
                _state.value = CaptureState.Failed("캡처 세션을 시작하지 못했다: ${it.message}")
                return
            }
        if (mediaProjection == null) {
            _state.value = CaptureState.Failed("캡처 권한이 거부되었다")
            return
        }
        mediaProjection.registerCallback(projectionCallback, null)
        projection = mediaProjection

        val sampleRate = AudioOutput.preferredSampleRate(audioManager)
        val channels = 2

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Without this our own processed output is captured again on the next pass.
            .excludeUid(Process.myUid())
            .build()

        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AndroidAudioFormat.CHANNEL_IN_STEREO,
            AndroidAudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        val audioRecord = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBytes * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrElse {
            _state.value = CaptureState.Failed("오디오 캡처를 열지 못했다: ${it.message}")
            releaseProjection()
            return
        }

        // A second of slack absorbs scheduling jitter between the capture and playback threads
        // without adding meaningful latency, because the reader drains it as fast as it fills.
        val ringBuffer = RingBufferSource(sampleRate, channels, capacityFrames = sampleRate)
        val output = AudioOutput(sampleRate, channels, route)
        val routedDevice = if (route == OutputRoute.PREFERRED_DEVICE) {
            output.applyPreferredDevice(audioManager)
        } else {
            null
        }
        val playback = PlaybackEngine(ringBuffer, output, blockFrames = BLOCK_FRAMES)

        record = audioRecord
        ring = ringBuffer
        engine = playback

        capturing = true
        startedAtMs = SystemClock.elapsedRealtime()

        audioRecord.startRecording()
        playback.start()
        captureThread = thread(name = "mmm-capture", isDaemon = true) { captureLoop(audioRecord, ringBuffer) }
        telemetryThread = thread(name = "mmm-telemetry", isDaemon = true) { telemetryLoop() }

        val captureLatencyMs = (minBytes * 2 / (4 * channels)) * 1000 / sampleRate
        _state.value = CaptureState.Running(
            sampleRate = sampleRate,
            channels = channels,
            route = route,
            latencyMs = captureLatencyMs + playback.latencyFrames * 1000 / sampleRate,
            outputDevice = routedDevice?.productName?.toString()
                ?: output.routedDevice?.productName?.toString(),
        )
    }

    private fun captureLoop(audioRecord: AudioRecord, ringBuffer: RingBufferSource) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val channels = ringBuffer.channels
        val interleaved = FloatArray(BLOCK_FRAMES * channels)
        val block = AudioBuffer(channels, BLOCK_FRAMES)

        while (capturing) {
            val read = audioRecord.read(interleaved, 0, interleaved.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (read < 0) {
                    stopCapture("캡처 읽기 실패 ($read)")
                    return
                }
                continue
            }
            block.readInterleaved(interleaved, read / channels)
            ringBuffer.write(block)
        }
    }

    private fun telemetryLoop() {
        while (capturing) {
            val playback = engine
            val ringBuffer = ring
            if (playback != null && ringBuffer != null) {
                _telemetry.value = CaptureTelemetry(
                    inputRmsDb = playback.inputMeter.rmsDb,
                    inputPeakDb = playback.inputMeter.peakDb,
                    outputRmsDb = playback.outputMeter.rmsDb,
                    sawSignal = playback.inputMeter.sawSignal,
                    underrunFrames = ringBuffer.underrunFrames,
                    overrunFrames = ringBuffer.overrunFrames,
                    framesProcessed = playback.framesProcessed,
                    levelCorrectionDb = playback.levelMatcher?.appliedCorrectionDb ?: 0.0,
                    error = playback.lastError,
                )
            }
            Thread.sleep(TELEMETRY_INTERVAL_MS)
        }
    }

    private fun stopCapture(reason: String?) {
        if (!capturing && projection == null) {
            if (reason != null) _state.value = CaptureState.Failed(reason)
            return
        }
        capturing = false

        captureThread?.join(500)
        captureThread = null
        telemetryThread = null

        engine?.release()
        engine = null

        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        ring = null

        releaseProjection()

        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).allowedCapturePolicy =
            AudioAttributes.ALLOW_CAPTURE_BY_ALL

        _state.value = if (reason != null) CaptureState.Failed(reason) else CaptureState.Idle
    }

    private fun releaseProjection() {
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
    }

    /** Milliseconds since capture started, for [CaptureTelemetry.looksBlocked]. */
    public val runningMs: Long
        get() = if (startedAtMs == 0L) 0 else SystemClock.elapsedRealtime() - startedAtMs

    /** Installs the processing for the stimulus currently being auditioned. */
    public fun setArtifact(processor: AudioProcessor, levelMatched: Boolean = true) {
        engine?.setArtifact(processor, levelMatched)
    }

    public fun clearArtifact() {
        engine?.clearArtifact()
    }

    // ---------------------------------------------------------------- foreground

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "실시간 캡처",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("실시간 청음 훈련")
            .setContentText("다른 앱의 오디오를 캡처하는 중")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    public companion object {
        public const val ACTION_START: String = "mmm.capture.START"
        public const val ACTION_STOP: String = "mmm.capture.STOP"
        public const val EXTRA_RESULT_CODE: String = "resultCode"
        public const val EXTRA_RESULT_DATA: String = "resultData"
        public const val EXTRA_ROUTE: String = "route"

        private const val CHANNEL_ID = "mmm_capture"
        private const val NOTIFICATION_ID = 0x4d4d
        private const val TELEMETRY_INTERVAL_MS = 100L

        /** 512 frames is ~10.7 ms at 48 kHz - small enough to keep the round trip usable. */
        private const val BLOCK_FRAMES = 512

        public fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            route: OutputRoute,
        ): Intent = Intent(context, SystemAudioCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_ROUTE, route.id)
        }

        public fun stopIntent(context: Context): Intent =
            Intent(context, SystemAudioCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
