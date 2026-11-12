package mmm.dsp.harmonics

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadCoefficients
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter

/**
 * The treble roll-off of a non-oversampling DAC, as a tone setting: -1.0 dB at 12 kHz and -3.2 dB
 * at 20 kHz, falling smoothly between and barely touching anything below.
 *
 * Those are close to what a NOS DAC's zero-order hold does at 44.1 kHz (a sin x / x response: -1.1
 * and -3.2 dB). This is a profile inspired by that, not a model of any DAC: it is a single high
 * shelf whose frequency and depth are solved for at each sample rate so the two points land where
 * they should, and it says nothing about the images and filter-less output a real NOS DAC has.
 * It is its own stage, separate from the harmonics, and changes no harmonic.
 *
 * [mix] glides between bypass (0) and the full roll-off (1), so switching it is silent.
 */
public class NosRolloff : AudioProcessor {

    private var filter = BiquadFilter(2)
    private var dry = AudioBuffer(1, 1)
    private var mix = 0.0
    private var target = 0.0
    private var step = 0.0
    private var rampFrames = 1

    /** 1 to apply the roll-off, 0 to bypass it. */
    public fun setEnabled(on: Boolean) {
        val wanted = if (on) 1.0 else 0.0
        if (wanted == target) return
        target = wanted
        step = (target - mix) / rampFrames
    }

    public val idle: Boolean get() = mix == 0.0 && target == 0.0

    override fun prepare(format: AudioFormat) {
        filter = BiquadFilter(format.channels).apply {
            coefficients = design(format.sampleRate)
            prepare(format)
        }
        dry = AudioBuffer(format.channels, format.maxFrames)
        rampFrames = (RAMP_SECONDS * format.sampleRate).toInt().coerceAtLeast(1)
        mix = target
        step = 0.0
    }

    override fun process(buffer: AudioBuffer) {
        if (idle) return
        if (mix == 1.0 && target == 1.0) {
            filter.process(buffer)
            return
        }
        if (buffer.channels != dry.channels) return
        if (buffer.frames > dry.capacity) dry = AudioBuffer(buffer.channels, buffer.frames)
        dry.frames = buffer.frames
        for (ch in 0 until buffer.channels) System.arraycopy(buffer.data[ch], 0, dry.data[ch], 0, buffer.frames)
        filter.process(buffer)
        val start = mix
        for (ch in 0 until buffer.channels) {
            var m = start
            val wet = buffer.data[ch]
            val raw = dry.data[ch]
            for (i in 0 until buffer.frames) {
                if (m != target) m = if (step > 0) minOf(target, m + step) else maxOf(target, m + step)
                wet[i] = (raw[i] + (wet[i] - raw[i]) * m).toFloat()
            }
            mix = m
        }
        if (mix == 0.0 && target == 0.0) filter.reset()
    }

    override fun reset() {
        filter.reset()
    }

    public companion object {
        public const val POINT_1_HZ: Double = 12000.0
        public const val POINT_1_DB: Double = -1.0
        public const val POINT_2_HZ: Double = 20000.0
        public const val POINT_2_DB: Double = -3.2
        private const val RAMP_SECONDS = 0.05

        /**
         * The shelf for [sampleRate], found by a coarse-to-fine search over its frequency and
         * depth (and two Q values) for the least squared miss at the two target points.
         */
        public fun design(sampleRate: Int): BiquadCoefficients {
            var best: Triple<Double, Double, Double>? = null
            var bestError = Double.MAX_VALUE
            fun consider(frequency: Double, q: Double, gain: Double) {
                if (frequency >= sampleRate * 0.49) return
                val c = BiquadDesign.highShelfQ(frequency, q, gain, sampleRate)
                val e1 = c.magnitudeDbAt(POINT_1_HZ, sampleRate) - POINT_1_DB
                val e2 = c.magnitudeDbAt(minOf(POINT_2_HZ, sampleRate * 0.49), sampleRate) - POINT_2_DB
                val error = e1 * e1 + e2 * e2
                if (error < bestError) {
                    bestError = error
                    best = Triple(frequency, q, gain)
                }
            }
            for (q in doubleArrayOf(0.6, 0.707)) {
                var f = 8000.0
                while (f < sampleRate * 0.49) {
                    var g = -20.0
                    while (g < 0.0) {
                        consider(f, q, g)
                        g += 0.5
                    }
                    f += 500.0
                }
            }
            val (f0, q0, g0) = best!!
            var f = f0 - 500
            while (f <= f0 + 500) {
                var g = g0 - 0.5
                while (g <= g0 + 0.5) {
                    consider(f, q0, g)
                    g += 0.02
                }
                f += 25.0
            }
            val (f1, q1, g1) = best!!
            return BiquadDesign.highShelfQ(f1, q1, g1, sampleRate)
        }

        /** What the roll-off does at [frequency], for drawing. */
        public fun responseDb(frequency: Double, sampleRate: Int = 48000): Double =
            design(sampleRate).magnitudeDbAt(frequency, sampleRate)
    }
}
