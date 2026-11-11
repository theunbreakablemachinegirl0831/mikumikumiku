package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * How much second and third harmonic to make from one stretch of the spectrum.
 *
 * Levels are in dB relative to a full-scale tone: at 0 dB a full-scale sine grows a harmonic as
 * large as itself, at -40 dB one a hundredth its size. [OFF_DB] and below make none.
 */
public data class TubeBand(
    val fromHz: Double,
    val toHz: Double,
    val secondDb: Double = OFF_DB,
    val thirdDb: Double = OFF_DB,
) {
    public val active: Boolean get() = secondDb > OFF_DB || thirdDb > OFF_DB

    public companion object {
        public const val OFF_DB: Double = -150.0
        public const val MAX_DB: Double = 0.0
    }
}

public data class TubeSettings(val bands: List<TubeBand> = DEFAULT_BANDS) {
    public val active: Boolean get() = bands.any { it.active }

    public companion object {
        /** Bass, midrange and treble, split where the ear hears warmth, body and edge. */
        public val DEFAULT_BANDS: List<TubeBand> = listOf(
            TubeBand(20.0, 200.0),
            TubeBand(200.0, 2000.0),
            TubeBand(2000.0, 20000.0),
        )
    }
}

/**
 * Adds harmonics the way a valve stage does, band by band.
 *
 * A valve amplifier's sound is largely which harmonics it adds and how many: a single-ended
 * triode mostly the second, an octave up and consonant; a push-pull stage mostly the third.
 * Here each band of the signal is squared and cubed to make exactly those two, and the listener
 * sets how much of each is mixed back in. Like a real stage, quieter passages get
 * proportionally less: the second harmonic of a tone falls 2 dB for every dB the tone falls, the
 * third 3 dB.
 *
 * The harmonics are made from a copy of the band and added to the untouched signal, so nothing
 * is filtered out of the music itself. Squaring and cubing double and triple frequencies, which
 * would fold back from the top of the spectrum as tones that are not harmonics at all, so no
 * band is allowed to feed the shaper above a sixth of the sample rate.
 */
public class TubeProcessor(private val settings: TubeSettings) : AudioProcessor {

    private class BandState(
        val highPass: BiquadFilter,
        val lowPass: List<BiquadFilter>,
        val gain2: Float,
        val gain3: Float,
        channels: Int,
    ) {
        /** Running mean of 2x², which is the squared amplitude of a steady tone. */
        val power = DoubleArray(channels)
        /** DC blocker state on the generated harmonics. */
        val blockIn = DoubleArray(channels)
        val blockOut = DoubleArray(channels)
    }

    private var bands: List<BandState> = emptyList()
    private var scratch = AudioBuffer(1, 1)
    private var follow = 0.0
    private var block = 0.0

    override fun prepare(format: AudioFormat) {
        val rate = format.sampleRate
        val ceiling = rate / ALIAS_GUARD
        bands = settings.bands.filter { it.active && it.fromHz < ceiling }.map { band ->
            val top = min(band.toHz, ceiling)
            BandState(
                highPass = BiquadFilter(format.channels).apply {
                    coefficients = BiquadDesign.highPass(band.fromHz, BUTTERWORTH_Q, rate)
                    prepare(format)
                },
                // Two stages: a fourth-order edge, so little above the band reaches the shaper.
                lowPass = List(2) {
                    BiquadFilter(format.channels).apply {
                        coefficients = BiquadDesign.lowPass(top, BUTTERWORTH_Q, rate)
                        prepare(format)
                    }
                },
                gain2 = gainOf(band.secondDb),
                gain3 = gainOf(band.thirdDb),
                channels = format.channels,
            )
        }
        scratch = AudioBuffer(format.channels, format.maxFrames)
        follow = 1.0 - exp(-1.0 / (FOLLOW_SECONDS * rate))
        block = exp(-2.0 * Math.PI * DC_BLOCK_HZ / rate)
    }

    override fun process(buffer: AudioBuffer) {
        if (bands.isEmpty()) return
        if (buffer.channels != scratch.channels) return
        // A block larger than promised costs one allocation rather than a dropped effect.
        if (buffer.frames > scratch.capacity) scratch = AudioBuffer(buffer.channels, buffer.frames)
        for (band in bands) {
            scratch.frames = buffer.frames
            for (ch in 0 until buffer.channels) System.arraycopy(buffer.data[ch], 0, scratch.data[ch], 0, buffer.frames)
            band.highPass.process(scratch)
            for (stage in band.lowPass) stage.process(scratch)

            for (ch in 0 until buffer.channels) {
                val source = scratch.data[ch]
                val out = buffer.data[ch]
                var power = band.power[ch]
                var blockIn = band.blockIn[ch]
                var blockOut = band.blockOut[ch]
                for (i in 0 until buffer.frames) {
                    val x = source[i].toDouble()
                    val squared = 2.0 * x * x
                    power += follow * (squared - power)
                    // For a·cos: 2x² - a² = a²·cos 2θ, and 4x³ - 3a²x = a³·cos 3θ. The running
                    // power stands in for a², which takes the fundamental back out of the cube.
                    val made = band.gain2 * (squared - power) + band.gain3 * (4.0 * x * x * x - 3.0 * power * x)
                    blockOut = made - blockIn + block * blockOut
                    blockIn = made
                    out[i] = (out[i] + blockOut).toFloat()
                }
                band.power[ch] = power
                band.blockIn[ch] = blockIn
                band.blockOut[ch] = blockOut
            }
        }
    }

    override fun reset() {
        for (band in bands) {
            band.highPass.reset()
            band.lowPass.forEach(BiquadFilter::reset)
            band.power.fill(0.0)
            band.blockIn.fill(0.0)
            band.blockOut.fill(0.0)
        }
    }

    private fun gainOf(db: Double): Float =
        if (db <= TubeBand.OFF_DB) 0f else 10.0.pow(db.coerceAtMost(TubeBand.MAX_DB) / 20.0).toFloat()

    private companion object {
        const val BUTTERWORTH_Q = 0.7071
        /** The third harmonic of anything below rate/6 stays under half the rate. */
        const val ALIAS_GUARD = 6.0
        const val FOLLOW_SECONDS = 0.02
        const val DC_BLOCK_HZ = 10.0
    }
}
