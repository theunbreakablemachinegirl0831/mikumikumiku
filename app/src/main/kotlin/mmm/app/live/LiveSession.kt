package mmm.app.live

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mmm.audio.AudioOutput
import mmm.audio.OutputRoute
import mmm.audio.PlaybackEngine
import mmm.dsp.SignalGenerator
import mmm.dsp.stream.ClipSource
import mmm.source.capture.CaptureController
import mmm.source.capture.CaptureState
import mmm.source.usb.UsbAudioController
import mmm.source.usb.UsbAudioSink
import mmm.source.usb.UsbIsoStreamer
import mmm.source.usb.UsbStreamStats
import mmm.training.ArtifactSpec
import kotlin.math.pow
import kotlin.math.sqrt

/** Which of the two things that can drive the DAC is doing so. */
enum class LiveOutput { NONE, TEST_TONE, CAPTURE }

data class LiveStatus(
    val output: LiveOutput = LiveOutput.NONE,
    /** Rate the DAC is claimed at, which is also the rate capture has to run at. */
    val sampleRate: Int = 0,
    val stream: UsbStreamStats = UsbStreamStats(),
    val message: String? = null,
)

/**
 * The live mode's long-lived half: the claimed DAC, the stream to it, and live capture.
 *
 * Held by the Application rather than a screen, because the claim and the capture have to survive
 * moving from the setup screen to an exercise and back. Only one thing drives the DAC at a time -
 * the test tone or the captured audio - and switching between them reopens the stream, since the
 * two need different pacing (see [UsbIsoStreamer]).
 */
class LiveSession(private val context: Context, private val scope: CoroutineScope) {

    val usb = UsbAudioController(context)
    val capture = CaptureController(context, scope)

    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status.asStateFlow()

    private var streamer: UsbIsoStreamer? = null
    private var toneEngine: PlaybackEngine? = null
    private var poller: Job? = null
    private var started = false

    /** Starts watching for DACs. Idempotent. */
    fun start() {
        if (started) return
        started = true
        usb.start()
        scope.launch {
            capture.state.collect { state ->
                if (state is CaptureState.Failed && _status.value.output == LiveOutput.CAPTURE) {
                    _status.value = _status.value.copy(output = LiveOutput.NONE, message = state.reason)
                }
            }
        }
    }

    /** The phone's own output rate. Capture runs at it, so the DAC is claimed at it too. */
    fun preferredRate(): Int =
        AudioOutput.preferredSampleRate(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    /** Claims the selected DAC at [preferredRate], refusing up front if it cannot play that rate. */
    fun claim() {
        val rate = preferredRate()
        val candidate = usb.report.value.selected
        val alternate = candidate?.function?.bestMatch(rate)
        if (candidate == null || alternate == null) {
            say("점유할 USB DAC이 없다")
            return
        }
        if (!alternate.supportsRate(rate)) {
            say("이 DAC은 폰의 출력 레이트 ${rate / 1000.0} kHz를 지원하지 않는다 - 리샘플링은 아직 없다")
            return
        }
        val result = usb.claimExclusively(rate)
        _status.value = _status.value.copy(
            sampleRate = if (result?.claimed == true) rate else 0,
            message = result?.detail,
        )
    }

    fun release() {
        stopOutput()
        usb.releaseClaim()
        _status.value = LiveStatus(message = "DAC을 Android에 돌려주었다")
    }

    /**
     * Quiet pink noise straight to the DAC. The first thing to try after claiming: it exercises the
     * whole USB path with nothing else involved, so if this is silent the problem is the stream.
     */
    fun startTestTone() {
        stopOutput()
        val stream = usb.openStream(clockedProducer = false) ?: return say("먼저 DAC을 점유한다")
        val rate = stream.sampleRate
        val noise = SignalGenerator.pinkNoise(frames = rate * TONE_SECONDS, channels = stream.channels)
        scaleToRms(noise, TONE_RMS_DBFS)
        fadeIn(noise, (rate * FADE_SECONDS).toInt())

        val engine = PlaybackEngine(ClipSource(noise, rate, loop = true), UsbAudioSink(stream))
        streamer = stream
        toneEngine = engine
        engine.start()
        _status.value = _status.value.copy(output = LiveOutput.TEST_TONE, message = null)
        watch()
    }

    /** Call with the MediaProjection grant. Captured audio is played to the DAC, and only there. */
    fun startCapture(resultCode: Int, data: Intent) {
        stopOutput()
        val stream = usb.openStream(clockedProducer = true) ?: return say("먼저 DAC을 점유한다")
        streamer = stream
        capture.start(resultCode, data, OutputRoute.MEDIA, UsbAudioSink(stream))
        _status.value = _status.value.copy(output = LiveOutput.CAPTURE, message = null)
        watch()
    }

    /** Stops whatever is driving the DAC; the claim is kept. */
    fun stopOutput() {
        poller?.cancel()
        poller = null
        toneEngine?.release()
        toneEngine = null
        if (_status.value.output == LiveOutput.CAPTURE) capture.stop()
        streamer?.stop()
        streamer = null
        _status.value = _status.value.copy(output = LiveOutput.NONE, stream = UsbStreamStats())
    }

    /** Puts [spec] on the live stream, or takes processing off with null. */
    fun setArtifact(spec: ArtifactSpec?, levelMatched: Boolean) {
        if (spec == null || spec == ArtifactSpec.None) {
            capture.clearArtifact()
        } else {
            capture.setArtifact(spec.createProcessor(), levelMatched)
        }
    }

    val capturing: Boolean
        get() = _status.value.output == LiveOutput.CAPTURE && capture.state.value is CaptureState.Running

    private fun watch() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                streamer?.let { _status.value = _status.value.copy(stream = it.stats()) }
                delay(POLL_MS)
            }
        }
    }

    private fun say(message: String) {
        _status.value = _status.value.copy(message = message)
    }

    private fun scaleToRms(buffer: mmm.dsp.AudioBuffer, dbfs: Double) {
        var sum = 0.0
        for (ch in 0 until buffer.channels) {
            val samples = buffer.data[ch]
            for (i in 0 until buffer.frames) sum += samples[i] * samples[i]
        }
        val rms = sqrt(sum / (buffer.channels * buffer.frames).coerceAtLeast(1))
        if (rms <= 0.0) return
        val gain = (10.0.pow(dbfs / 20.0) / rms).toFloat()
        for (ch in 0 until buffer.channels) {
            val samples = buffer.data[ch]
            for (i in 0 until buffer.frames) samples[i] *= gain
        }
    }

    private fun fadeIn(buffer: mmm.dsp.AudioBuffer, frames: Int) {
        val length = frames.coerceAtMost(buffer.frames).coerceAtLeast(1)
        for (ch in 0 until buffer.channels) {
            val samples = buffer.data[ch]
            for (i in 0 until length) samples[i] *= i.toFloat() / length
        }
    }

    companion object {
        /**
         * -30 dBFS RMS: plainly audible at a normal volume setting, and nowhere near painful if the
         * DAC's own volume was left at maximum - which, in USB mode, the phone cannot see or change.
         */
        const val TONE_RMS_DBFS = -30.0
        private const val TONE_SECONDS = 4
        private const val FADE_SECONDS = 0.5
        private const val POLL_MS = 250L

        /** For the stats line: how deep the buffer is in milliseconds. */
        fun framesToMs(frames: Int, rate: Int): Int = if (rate > 0) frames * 1000 / rate else 0
    }
}
