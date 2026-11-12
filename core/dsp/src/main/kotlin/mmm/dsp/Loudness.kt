package mmm.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * ITU-R BS.1770 K-weighting, built from RBJ sections at the stream's own sample rate.
 *
 * Why this matters for a listening test: if a boosted stimulus is also *louder*, the learner can
 * answer every question on level alone and learn nothing. Every exercise is loudness-matched with
 * this meter before it is played.
 */
public class KWeightingFilter(channels: Int = 2) : AudioProcessor {

    private val shelf = BiquadFilter(channels)
    private val highPass = BiquadFilter(channels)

    override fun prepare(format: AudioFormat) {
        shelf.prepare(format)
        highPass.prepare(format)
        shelf.coefficients = BiquadDesign.highShelf(1681.97, 0.7071, 3.999, format.sampleRate)
        highPass.coefficients = BiquadDesign.highPass(38.13, 0.5, format.sampleRate)
    }

    override fun process(buffer: AudioBuffer) {
        shelf.process(buffer)
        highPass.process(buffer)
    }

    override fun reset() {
        shelf.reset()
        highPass.reset()
    }
}

/**
 * Gated integrated loudness in LUFS, per BS.1770-4.
 *
 * Feed it whole stimuli with [measure]; it applies K-weighting, blocks the signal into 400 ms
 * windows at 75 % overlap, then applies the absolute (-70 LUFS) and relative (-10 LU) gates.
 */
public object LoudnessMeter {

    private const val ABSOLUTE_GATE_LUFS = -70.0
    private const val RELATIVE_GATE_LU = -10.0
    private const val BLOCK_SECONDS = 0.400
    private const val OVERLAP = 0.75

    /** Per-channel weights; BS.1770 gives surround channels extra weight, stereo is unity. */
    private fun channelWeight(channel: Int, channels: Int): Double =
        if (channels <= 2) 1.0 else if (channel >= 3) 1.41 else 1.0

    /**
     * @return integrated loudness in LUFS, or [Double.NEGATIVE_INFINITY] if everything was gated
     *   out (i.e. silence).
     */
    public fun measure(signal: AudioBuffer, sampleRate: Int): Double {
        val weighted = signal.copy()
        val format = AudioFormat(sampleRate, weighted.channels, weighted.capacity)
        KWeightingFilter(weighted.channels).apply { prepare(format) }.process(weighted)

        val blockSize = (BLOCK_SECONDS * sampleRate).toInt()
        if (weighted.frames < blockSize) return measureUngated(weighted)
        val hop = (blockSize * (1.0 - OVERLAP)).toInt().coerceAtLeast(1)

        val blockPowers = ArrayList<Double>()
        var start = 0
        while (start + blockSize <= weighted.frames) {
            var power = 0.0
            for (ch in 0 until weighted.channels) {
                val c = weighted.data[ch]
                var sum = 0.0
                for (i in start until start + blockSize) {
                    val v = c[i].toDouble()
                    sum += v * v
                }
                power += channelWeight(ch, weighted.channels) * (sum / blockSize)
            }
            blockPowers += power
            start += hop
        }
        if (blockPowers.isEmpty()) return Double.NEGATIVE_INFINITY

        val aboveAbsolute = blockPowers.filter { powerToLufs(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return Double.NEGATIVE_INFINITY

        val relativeThreshold = powerToLufs(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { powerToLufs(it) > relativeThreshold }
        val kept = if (gated.isEmpty()) aboveAbsolute else gated
        return powerToLufs(kept.average())
    }

    private fun measureUngated(weighted: AudioBuffer): Double {
        if (weighted.frames == 0) return Double.NEGATIVE_INFINITY
        var power = 0.0
        for (ch in 0 until weighted.channels) {
            val c = weighted.data[ch]
            var sum = 0.0
            for (i in 0 until weighted.frames) {
                val v = c[i].toDouble()
                sum += v * v
            }
            power += channelWeight(ch, weighted.channels) * (sum / weighted.frames)
        }
        return powerToLufs(power)
    }

    private fun powerToLufs(power: Double): Double =
        -0.691 + 10.0 * log10(power.coerceAtLeast(1e-15))

    /**
     * Gain in dB that brings [signal] to [targetLufs]. Returns 0 for silence rather than an
     * infinite boost.
     */
    public fun gainToTargetDb(signal: AudioBuffer, sampleRate: Int, targetLufs: Double): Double {
        val measured = measure(signal, sampleRate)
        if (!measured.isFinite()) return 0.0
        return targetLufs - measured
    }
}

/**
 * Loudness-matches a processed stimulus back to its source.
 *
 * The training engine runs this on every generated stimulus so that the only difference between
 * the reference and the processed version is the artifact itself.
 */
public object LoudnessMatch {

    /** Hard limit on how much correction we will apply, so a pathological setting cannot blast the listener. */
    public const val MAX_CORRECTION_DB: Double = 18.0

    /**
     * Applies gain to [processed] in place so that its loudness matches [reference].
     * @return the gain applied, in dB.
     */
    public fun matchInPlace(processed: AudioBuffer, reference: AudioBuffer, sampleRate: Int): Double {
        val referenceLufs = LoudnessMeter.measure(reference, sampleRate)
        val processedLufs = LoudnessMeter.measure(processed, sampleRate)
        if (!referenceLufs.isFinite() || !processedLufs.isFinite()) return 0.0

        val correctionDb = (referenceLufs - processedLufs).coerceIn(-MAX_CORRECTION_DB, MAX_CORRECTION_DB)
        val gain = 10.0.pow(correctionDb / 20.0).toFloat()
        for (ch in 0 until processed.channels) {
            val c = processed.data[ch]
            for (i in 0 until processed.frames) c[i] *= gain
        }
        return correctionDb
    }
}
