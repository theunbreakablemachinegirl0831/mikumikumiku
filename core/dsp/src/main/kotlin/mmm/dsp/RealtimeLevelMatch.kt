package mmm.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Keeps a live-processed stream at the same perceived level as its input.
 *
 * The file mode can measure a whole stimulus twice and apply one exact gain; a cast/captured
 * stream cannot, so this tracks K-weighted short-term power on both sides of [inner] and applies a
 * slow, slew-limited correction. Slow on purpose - a fast corrector would fight the compression
 * exercises and partly undo the very artifact under test.
 *
 * @param timeConstantMs integration window for both level estimates
 * @param maxCorrectionDb clamp on the correction, mirroring [LoudnessMatch.MAX_CORRECTION_DB]
 * @param maxSlewDbPerSecond how fast the correction may move
 */
public class RealtimeLevelMatch(
    private val inner: AudioProcessor,
    private val timeConstantMs: Double = 400.0,
    private val maxCorrectionDb: Double = 12.0,
    private val maxSlewDbPerSecond: Double = 3.0,
) : AudioProcessor {

    private var sampleRate = 48000
    private var smoothing = 0.0
    private var scratch: AudioBuffer? = null

    private val inputWeighting = KWeightingFilter()
    private val outputWeighting = KWeightingFilter()
    private var inputMeter: AudioBuffer? = null
    private var outputMeter: AudioBuffer? = null

    private var inputPower = 0.0
    private var outputPower = 0.0
    private var correctionDb = 0.0

    /** Current correction, exposed for the UI so the listener can see it is not cheating. */
    public val appliedCorrectionDb: Double get() = correctionDb

    /** Set false to hear the artifact without level compensation (useful for demonstrations). */
    public var enabled: Boolean = true

    override fun prepare(format: AudioFormat) {
        sampleRate = format.sampleRate
        smoothing = exp(-1.0 / (timeConstantMs / 1000.0 * sampleRate))
        scratch = AudioBuffer(format.channels, format.maxFrames)
        inputMeter = AudioBuffer(format.channels, format.maxFrames)
        outputMeter = AudioBuffer(format.channels, format.maxFrames)
        inputWeighting.prepare(format)
        outputWeighting.prepare(format)
        inner.prepare(format)
        reset()
    }

    override fun reset() {
        inputPower = 0.0
        outputPower = 0.0
        correctionDb = 0.0
        inputWeighting.reset()
        outputWeighting.reset()
        inner.reset()
    }

    override val latencyFrames: Int get() = inner.latencyFrames

    override fun process(buffer: AudioBuffer) {
        if (!enabled) {
            inner.process(buffer)
            return
        }
        val pre = inputMeter ?: return inner.process(buffer)
        val post = outputMeter ?: return inner.process(buffer)

        pre.copyFrom(buffer)
        inputWeighting.process(pre)
        inputPower = integrate(inputPower, pre)

        inner.process(buffer)

        post.copyFrom(buffer)
        outputWeighting.process(post)
        outputPower = integrate(outputPower, post)

        updateCorrection(buffer.frames)
        applyCorrection(buffer)
    }

    private fun integrate(state: Double, weighted: AudioBuffer): Double {
        var acc = state
        val channels = weighted.channels
        for (i in 0 until weighted.frames) {
            var p = 0.0
            for (ch in 0 until channels) {
                val v = weighted.data[ch][i].toDouble()
                p += v * v
            }
            acc = (p / channels) + smoothing * (acc - (p / channels))
        }
        return acc
    }

    private fun updateCorrection(frames: Int) {
        // Below roughly -70 dBFS the estimate is noise; hold the last correction instead of chasing it.
        if (inputPower < 1e-8 || outputPower < 1e-8) return
        val desiredDb = (10.0 * log10(inputPower / outputPower))
            .coerceIn(-maxCorrectionDb, maxCorrectionDb)
        val maxStep = maxSlewDbPerSecond * frames / sampleRate
        correctionDb += (desiredDb - correctionDb).coerceIn(-maxStep, maxStep)
    }

    private fun applyCorrection(buffer: AudioBuffer) {
        if (kotlin.math.abs(correctionDb) < 0.01) return
        val gain = 10.0.pow(correctionDb / 20.0).toFloat()
        for (ch in 0 until buffer.channels) {
            val c = buffer.data[ch]
            for (i in 0 until buffer.frames) c[i] *= gain
        }
    }
}
