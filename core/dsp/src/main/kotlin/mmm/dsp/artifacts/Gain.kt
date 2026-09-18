package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import kotlin.math.pow

/** Flat gain with a short ramp, so level changes between stimuli never click. */
public class GainProcessor(gainDb: Double = 0.0, private val rampMs: Double = 20.0) : AudioProcessor {

    public var gainDb: Double = gainDb
        set(value) {
            field = value
            target = dbToLinear(value)
        }

    private var target: Float = dbToLinear(gainDb)
    private var current: Float = target
    private var step: Float = 0f

    override fun prepare(format: AudioFormat) {
        val rampFrames = (rampMs / 1000.0 * format.sampleRate).coerceAtLeast(1.0)
        step = (1f / rampFrames).toFloat()
        current = target
    }

    override fun reset() {
        current = target
    }

    override fun process(buffer: AudioBuffer) {
        if (current == target) {
            if (target == 1f) return
            for (ch in 0 until buffer.channels) {
                val c = buffer.data[ch]
                for (i in 0 until buffer.frames) c[i] *= target
            }
            return
        }
        // Ramp the first channel to derive the gain trajectory, then apply the same to the rest,
        // so all channels stay phase- and level-coherent.
        val trajectory = FloatArray(buffer.frames)
        var g = current
        for (i in 0 until buffer.frames) {
            g = when {
                g < target -> (g + step).coerceAtMost(target)
                g > target -> (g - step).coerceAtLeast(target)
                else -> target
            }
            trajectory[i] = g
        }
        current = g
        for (ch in 0 until buffer.channels) {
            val c = buffer.data[ch]
            for (i in 0 until buffer.frames) c[i] *= trajectory[i]
        }
    }

    public companion object {
        public fun dbToLinear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()
        public fun linearToDb(linear: Double): Double =
            20.0 * kotlin.math.log10(linear.coerceAtLeast(1e-12))
    }
}
