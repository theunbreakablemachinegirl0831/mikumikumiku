package mmm.dsp.harmonics

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * The last stage before the output: keeps peaks under [CEILING_DB] and does nothing else.
 *
 * Below the ceiling the gain is exactly 1, so music the EQ's preamp already left room for passes
 * bit for bit. A sample that would go over brings the gain down at once - no look-ahead, so no
 * added latency - and the gain recovers over [RELEASE_SECONDS]. All channels share one gain, so
 * a peak in one does not move the stereo image. It is a safety net under the harmonics and EQ,
 * not a loudness tool: the harmonics add level, and without it their peaks would hard-clip.
 */
public class PeakLimiter : AudioProcessor {

    private var gain = 1.0
    private var release = 0.0
    private val ceiling = 10.0.pow(CEILING_DB / 20)

    /** How far down the gain is now, in dB; 0 when the limiter is not acting. */
    public val reductionDb: Double get() = 20 * kotlin.math.log10(gain)

    override fun prepare(format: AudioFormat) {
        release = 1.0 - exp(-1.0 / (RELEASE_SECONDS * format.sampleRate))
        gain = 1.0
    }

    override fun process(buffer: AudioBuffer) {
        for (i in 0 until buffer.frames) {
            var peak = 0.0
            for (ch in 0 until buffer.channels) peak = maxOf(peak, abs(buffer.data[ch][i].toDouble()))
            val needed = if (peak * gain > ceiling) ceiling / peak else 1.0
            gain = if (needed < gain) needed else gain + (1.0 - gain) * release
            if (gain > 0.999999) gain = 1.0
            if (gain != 1.0) for (ch in 0 until buffer.channels) buffer.data[ch][i] = (buffer.data[ch][i] * gain).toFloat()
        }
    }

    override fun reset() {
        gain = 1.0
    }

    public companion object {
        public const val CEILING_DB: Double = -0.3
        public const val RELEASE_SECONDS: Double = 0.08
    }
}
