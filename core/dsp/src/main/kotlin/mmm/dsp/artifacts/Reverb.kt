package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import kotlin.math.pow

/**
 * Schroeder reverberator (four parallel combs into two series all-passes per channel).
 *
 * Deliberately simple and *uncoloured-ish*: the reverberation exercises are about hearing decay
 * time and wet level, so a character-heavy algorithm would add confounding timbre cues.
 * Per-channel delay lengths are offset slightly to decorrelate the channels.
 */
public class ReverbProcessor(
    decaySeconds: Double = 1.2,
    mix: Double = 0.2,
) : AudioProcessor {

    public var decaySeconds: Double = decaySeconds
        set(value) { field = value.coerceIn(0.05, 10.0); updateFeedback() }

    /** 0 = dry only, 1 = wet only. */
    public var mix: Double = mix
        set(value) { field = value.coerceIn(0.0, 1.0) }

    private var sampleRate = 48000
    private var channelStates: Array<ChannelState> = emptyArray()

    // Classic Schroeder prime-ish delays in milliseconds.
    private val combMs = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
    private val allPassMs = doubleArrayOf(5.0, 1.7)

    override fun prepare(format: AudioFormat) {
        sampleRate = format.sampleRate
        channelStates = Array(format.channels) { ch ->
            // A few percent of length offset per channel keeps the tails from summing to mono.
            val spread = 1.0 + ch * 0.021
            ChannelState(
                combs = Array(combMs.size) { DelayLine(msToFrames(combMs[it] * spread)) },
                allPasses = Array(allPassMs.size) { DelayLine(msToFrames(allPassMs[it] * spread)) },
            )
        }
        updateFeedback()
        reset()
    }

    override fun reset() {
        channelStates.forEach { it.reset() }
    }

    private fun msToFrames(ms: Double): Int = (ms / 1000.0 * sampleRate).toInt().coerceAtLeast(1)

    private var combFeedback = DoubleArray(4)

    private fun updateFeedback() {
        // RT60: feedback g such that the comb decays 60 dB over decaySeconds.
        combFeedback = DoubleArray(combMs.size) { i ->
            val delaySec = combMs[i] / 1000.0
            10.0.pow(-3.0 * delaySec / decaySeconds).coerceIn(0.0, 0.98)
        }
    }

    override fun process(buffer: AudioBuffer) {
        if (mix <= 0.0) return
        val wet = mix.toFloat()
        val dry = (1.0 - mix).toFloat()
        for (ch in 0 until buffer.channels) {
            val state = channelStates.getOrNull(ch) ?: continue
            val samples = buffer.data[ch]
            for (i in 0 until buffer.frames) {
                val x = samples[i]
                var acc = 0f
                for (c in state.combs.indices) {
                    acc += state.combs[c].processComb(x, combFeedback[c].toFloat())
                }
                acc *= 0.25f
                for (ap in state.allPasses) acc = ap.processAllPass(acc, 0.7f)
                samples[i] = dry * x + wet * acc
            }
        }
    }

    private class ChannelState(val combs: Array<DelayLine>, val allPasses: Array<DelayLine>) {
        fun reset() {
            combs.forEach { it.clear() }
            allPasses.forEach { it.clear() }
        }
    }

    private class DelayLine(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun clear() {
            buffer.fill(0f)
            index = 0
        }

        fun processComb(input: Float, feedback: Float): Float {
            val out = buffer[index]
            buffer[index] = input + out * feedback
            index = (index + 1) % buffer.size
            return out
        }

        fun processAllPass(input: Float, g: Float): Float {
            val delayed = buffer[index]
            val out = -g * input + delayed
            buffer[index] = input + g * out
            index = (index + 1) % buffer.size
            return out
        }
    }
}
