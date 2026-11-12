package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/** The flavours of non-linearity the distortion exercises can present. */
public enum class DistortionType(public val displayName: String) {
    /** Amplifier-style symmetric soft clipping - mostly odd harmonics. */
    SOFT_CLIP("소프트 클립 (Soft clip)"),

    /** Digital-style hard clipping - harsh, high-order odd harmonics. */
    HARD_CLIP("하드 클립 (Hard clip)"),

    /** Asymmetric transfer curve - adds second harmonic, the "tube warmth" character. */
    ASYMMETRIC("비대칭 (Asymmetric)"),

    /** Class-B style crossover notch near zero - very audible on quiet passages. */
    CROSSOVER("크로스오버 (Crossover)"),

    /** Coarse requantisation without dither - granular, level-dependent grunge. */
    QUANTIZATION("양자화 (Quantization)"),
}

/**
 * Non-linear distortion with a perceptually graded [amount] control.
 *
 * Two design decisions matter for the exercises:
 *
 * 1. **Level referenced.** The threshold-style curves track a peak envelope of the incoming signal
 *    instead of clipping at a fixed dBFS. On live captured audio we do not control the level, and
 *    a fixed threshold would mean "no distortion at all" on a quiet track and "destroyed" on a
 *    loud one - so the same [amount] would not be the same question twice.
 * 2. **Logarithmic.** [amount] is swept exponentially so the low end of the range is genuinely
 *    near-threshold. A linear drive control spends its whole bottom half already audible, which is
 *    useless for finding somebody's detection threshold.
 *
 * [amount] is not a THD percentage: real THD depends on the programme material. Use
 * `amount` for the difficulty ladder and measure actual THD+N if you need a number.
 */
public class DistortionProcessor(
    type: DistortionType = DistortionType.SOFT_CLIP,
    amount: Double = 0.25,
) : AudioProcessor {

    public var type: DistortionType = type
    public var amount: Double = amount
        set(value) { field = value.coerceIn(0.0, 1.0) }

    private var attackCoeff = 0.0
    private var releaseCoeff = 0.0
    private var envelope = 0.0

    override fun prepare(format: AudioFormat) {
        // 1 ms attack so transients are caught, 300 ms release so the threshold does not pump.
        attackCoeff = exp(-1.0 / (0.001 * format.sampleRate))
        releaseCoeff = exp(-1.0 / (0.300 * format.sampleRate))
        reset()
    }

    override fun reset() {
        envelope = 0.0
    }

    override fun process(buffer: AudioBuffer) {
        if (amount <= 0.0) return
        when (type) {
            DistortionType.SOFT_CLIP -> {
                val drive = logDrive(minDrive = 0.2, maxDrive = 40.0)
                val norm = 1.0 / tanh(drive)
                mapEnvelopeSamples(buffer) { x, env ->
                    if (env <= 1e-7) x else (env * tanh(drive * x / env) * norm).toFloat()
                }
            }

            DistortionType.ASYMMETRIC -> {
                val drive = logDrive(minDrive = 0.2, maxDrive = 25.0)
                // The asymmetry has to fade out with the drive as well: a fixed half-wave gain
                // difference produces second-harmonic energy even at unity drive, which would make
                // the low end of the difficulty ladder *more* distorted than the middle.
                val bias = 0.5 * amount
                val positiveDrive = drive * (1.0 + bias)
                val norm = 1.0 / tanh(positiveDrive)
                mapEnvelopeSamples(buffer) { x, env ->
                    if (env <= 1e-7) {
                        x
                    } else {
                        val normalised = x / env
                        val shaped =
                            if (x >= 0) tanh(positiveDrive * normalised) else tanh(drive * normalised)
                        (env * shaped * norm).toFloat()
                    }
                }
            }

            DistortionType.HARD_CLIP -> {
                // Clip away the top `fraction` of the running peak.
                val fraction = 0.92 * amount * amount
                val makeup = (1.0 / (1.0 - fraction)).toFloat()
                mapEnvelopeSamples(buffer) { x, env ->
                    val threshold = (env * (1.0 - fraction)).toFloat()
                    if (threshold <= 1e-6f) x else x.coerceIn(-threshold, threshold) * makeup
                }
            }

            DistortionType.CROSSOVER -> {
                val depth = 0.06 * amount
                mapEnvelopeSamples(buffer) { x, env ->
                    val notch = (env * depth).toFloat()
                    if (notch <= 1e-7f) {
                        x
                    } else {
                        val a = abs(x)
                        if (a <= notch) 0f else sign(x) * (a - notch) / (1f - notch)
                    }
                }
            }

            DistortionType.QUANTIZATION -> {
                // 20 bits (inaudible) down to 3 bits (obvious), referenced to the running peak so
                // the step size stays a fixed fraction of the signal rather than of full scale.
                val bits = 20.0 - 17.0 * amount
                val steps = 2.0.pow(bits - 1.0)
                mapEnvelopeSamples(buffer) { x, env ->
                    if (env <= 1e-7) {
                        x
                    } else {
                        val scale = steps / env
                        (floor(x * scale + 0.5) / scale).toFloat()
                    }
                }
            }
        }
    }

    /** Exponential map from [amount] onto a drive range, so 0 is truly transparent. */
    private fun logDrive(minDrive: Double, maxDrive: Double): Double =
        minDrive * (maxDrive / minDrive).pow(amount)

    /**
     * Applies [f] sample by sample with the current stereo-linked peak envelope, so the threshold
     * curves stay level-referenced without the channels drifting apart.
     */
    private inline fun mapEnvelopeSamples(buffer: AudioBuffer, f: (Float, Double) -> Float) {
        for (i in 0 until buffer.frames) {
            var peak = 0f
            for (ch in 0 until buffer.channels) {
                val a = abs(buffer.data[ch][i])
                if (a > peak) peak = a
            }
            val coeff = if (peak > envelope) attackCoeff else releaseCoeff
            envelope = peak + coeff * (envelope - peak)
            for (ch in 0 until buffer.channels) {
                buffer.data[ch][i] = f(buffer.data[ch][i], envelope)
            }
        }
    }
}
