package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Stereo-linked feed-forward compressor with a soft knee.
 *
 * Channel-linked on purpose: independent per-channel gain reduction would shift the stereo image,
 * which is a second audible cue and would let the learner answer the dynamics questions for the
 * wrong reason.
 */
public class CompressionProcessor(
    thresholdDb: Double = -20.0,
    ratio: Double = 4.0,
    attackMs: Double = 10.0,
    releaseMs: Double = 150.0,
    kneeDb: Double = 6.0,
    makeupGainDb: Double = 0.0,
) : AudioProcessor {

    public var thresholdDb: Double = thresholdDb
    public var ratio: Double = ratio
        set(value) { field = value.coerceAtLeast(1.0) }
    public var kneeDb: Double = kneeDb
        set(value) { field = value.coerceAtLeast(0.0) }
    public var makeupGainDb: Double = makeupGainDb

    public var attackMs: Double = attackMs
        set(value) { field = value.coerceAtLeast(0.1); recomputeCoefficients() }
    public var releaseMs: Double = releaseMs
        set(value) { field = value.coerceAtLeast(1.0); recomputeCoefficients() }

    private var sampleRate: Int = 48000
    private var attackCoeff = 0.0
    private var releaseCoeff = 0.0

    /** Envelope in dB, held across blocks so the release tail is continuous. */
    private var envelopeDb = -120.0

    /** Most recent gain reduction, for the UI meter. */
    @Volatile
    public var gainReductionDb: Double = 0.0
        private set

    override fun prepare(format: AudioFormat) {
        sampleRate = format.sampleRate
        recomputeCoefficients()
        reset()
    }

    override fun reset() {
        envelopeDb = -120.0
        gainReductionDb = 0.0
    }

    private fun recomputeCoefficients() {
        attackCoeff = exp(-1.0 / (attackMs / 1000.0 * sampleRate))
        releaseCoeff = exp(-1.0 / (releaseMs / 1000.0 * sampleRate))
    }

    override fun process(buffer: AudioBuffer) {
        if (ratio <= 1.0 && makeupGainDb == 0.0) return
        val makeup = 10.0.pow(makeupGainDb / 20.0).toFloat()
        var lastGr = 0.0

        for (i in 0 until buffer.frames) {
            // Link detection: the loudest channel drives the gain for all of them.
            var peak = 0f
            for (ch in 0 until buffer.channels) {
                val a = abs(buffer.data[ch][i])
                if (a > peak) peak = a
            }
            val inputDb = 20.0 * log10(peak.toDouble().coerceAtLeast(1e-7))

            val coeff = if (inputDb > envelopeDb) attackCoeff else releaseCoeff
            envelopeDb = inputDb + coeff * (envelopeDb - inputDb)

            val overshoot = envelopeDb - thresholdDb
            val reductionDb = when {
                kneeDb > 0 && overshoot > -kneeDb / 2 && overshoot < kneeDb / 2 -> {
                    val x = overshoot + kneeDb / 2
                    (1.0 / ratio - 1.0) * x * x / (2.0 * kneeDb)
                }
                overshoot >= kneeDb / 2 -> (1.0 / ratio - 1.0) * overshoot
                else -> 0.0
            }
            lastGr = reductionDb

            val gain = (10.0.pow(reductionDb / 20.0).toFloat()) * makeup
            for (ch in 0 until buffer.channels) buffer.data[ch][i] *= gain
        }
        gainReductionDb = lastGr
    }
}
