package mmm.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic stimuli.
 *
 * "How to Listen" uses both music and noise: noise is the honest test of a spectral artifact
 * because it excites every band equally, while music is what the learner actually has to transfer
 * the skill to. The trainer offers both, so both live here.
 */
public object SignalGenerator {

    public fun sine(
        frequencyHz: Double,
        frames: Int,
        sampleRate: Int,
        channels: Int = 2,
        amplitude: Float = 0.5f,
    ): AudioBuffer = AudioBuffer(channels, frames).apply {
        this.frames = frames
        val w = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until frames) {
            val v = (amplitude * sin(w * i)).toFloat()
            for (ch in 0 until channels) data[ch][i] = v
        }
    }

    public fun whiteNoise(
        frames: Int,
        channels: Int = 2,
        amplitude: Float = 0.2f,
        random: Random = Random.Default,
    ): AudioBuffer = AudioBuffer(channels, frames).apply {
        this.frames = frames
        for (ch in 0 until channels) {
            val c = data[ch]
            for (i in 0 until frames) c[i] = ((random.nextFloat() * 2f) - 1f) * amplitude
        }
    }

    /**
     * Pink noise via the Voss-McCartney / Paul Kellet filter - flat per octave, which is what the
     * band-identification exercises need so no band is inherently easier to hear.
     */
    public fun pinkNoise(
        frames: Int,
        channels: Int = 2,
        amplitude: Float = 0.2f,
        random: Random = Random.Default,
    ): AudioBuffer = AudioBuffer(channels, frames).apply {
        this.frames = frames
        for (ch in 0 until channels) {
            val c = data[ch]
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
            for (i in 0 until frames) {
                val white = random.nextDouble() * 2.0 - 1.0
                b0 = 0.99886 * b0 + white * 0.0555179
                b1 = 0.99332 * b1 + white * 0.0750759
                b2 = 0.96900 * b2 + white * 0.1538520
                b3 = 0.86650 * b3 + white * 0.3104856
                b4 = 0.55000 * b4 + white * 0.5329522
                b5 = -0.7616 * b5 - white * 0.0168980
                val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
                b6 = white * 0.115926
                c[i] = (pink * 0.11 * amplitude).toFloat()
            }
        }
    }

    /** Logarithmic sine sweep, for impulse-response style demonstrations in the reference screen. */
    public fun logSweep(
        startHz: Double,
        endHz: Double,
        frames: Int,
        sampleRate: Int,
        channels: Int = 2,
        amplitude: Float = 0.4f,
    ): AudioBuffer = AudioBuffer(channels, frames).apply {
        this.frames = frames
        val duration = frames.toDouble() / sampleRate
        val k = kotlin.math.ln(endHz / startHz)
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val phase = 2.0 * PI * startHz * duration / k * (kotlin.math.exp(t * k / duration) - 1.0)
            val v = (amplitude * sin(phase)).toFloat()
            for (ch in 0 until channels) data[ch][i] = v
        }
    }

    /** Applies a linear fade in/out, so switching stimuli never clicks. */
    public fun applyFades(buffer: AudioBuffer, fadeFrames: Int) {
        val n = fadeFrames.coerceAtMost(buffer.frames / 2)
        if (n <= 0) return
        for (ch in 0 until buffer.channels) {
            val c = buffer.data[ch]
            for (i in 0 until n) {
                val g = i.toFloat() / n
                c[i] *= g
                c[buffer.frames - 1 - i] *= g
            }
        }
    }
}
