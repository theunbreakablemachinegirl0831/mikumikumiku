package mmm.dsp.harmonics

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Adds harmonics of any order up to [HarmonicSettings.MAX_ORDER], the way a valve stage grows
 * them: the louder the music, the more, and higher orders faster.
 *
 * For a tone of amplitude a, x = a·cos θ, the n-th harmonic is a^n·T_n(x/a), with T_n the n-th
 * Chebyshev polynomial - cos nθ is exactly T_n(cos θ). Those come from one recurrence,
 * a^(n+1)·T_(n+1) = 2x·a^n·T_n − a²·a^(n-1)·T_(n-1), so every order costs two multiplications.
 * The amplitude a is followed from the signal's running power - two one-pole averages in a
 * row, so its ripple at twice the frequency is small enough not to smear the higher orders - and
 * never allowed below the sample itself, which keeps x/a inside -1..1 where the polynomials stay bounded: a transient
 * the follower has not caught up with is shaped gently instead of blowing up.
 *
 * Each order's gain is the size of the harmonic a full-scale tone gets. A tone 20 dB down gets
 * its 2nd harmonic 40 dB down and its 5th 100 dB down - the falling-away that makes valve
 * distortion sound like richness at moderate levels rather than like breakage.
 *
 * The signal is split into bass, midrange and treble, each shaped on its own, and an order is
 * only made in a band whose top, times that order, stays below half the sample rate: a harmonic
 * above that would fold back down as a tone that is no harmonic at all. So the bass can take every
 * order, the midrange about ten, and the treble only the second and third. The harmonics are
 * added to the untouched signal; nothing is taken out of the music.
 *
 * Settings change through [update] while playing, and each gain glides to its new value over
 * [RAMP_SECONDS], so a preset change or a dragged slider never clicks.
 */
public class HarmonicProcessor(drive: HarmonicDrive) : AudioProcessor {

    private class Band(
        val highPass: BiquadFilter,
        val lowPass: List<BiquadFilter>,
        /** The highest order this band may make without aliasing. */
        val maxOrder: Int,
        channels: Int,
        val follow: Double,
    ) {
        val power = DoubleArray(channels)
        val smoothed = DoubleArray(channels)
        val blockIn = DoubleArray(channels)
        val blockOut = DoubleArray(channels)
        val gains = DoubleArray(HarmonicSettings.MAX_ORDER + 1)
        val steps = DoubleArray(HarmonicSettings.MAX_ORDER + 1)
    }

    private var target: HarmonicDrive = drive
    private var bands: List<Band> = emptyList()
    private var scratch = AudioBuffer(1, 1)
    private var block = 0.0
    private var rampFrames = 0
    private var rampLeft = 0

    override fun prepare(format: AudioFormat) {
        val rate = format.sampleRate
        val ceiling = rate * NYQUIST_MARGIN / 3
        bands = BAND_EDGES.map { (from, to) ->
            val top = min(to, ceiling)
            Band(
                highPass = BiquadFilter(format.channels).apply {
                    coefficients = BiquadDesign.highPass(from, BUTTERWORTH_Q, rate)
                    prepare(format)
                },
                // Two stages: a fourth-order edge, so little above the band reaches the shaper.
                lowPass = List(2) {
                    BiquadFilter(format.channels).apply {
                        coefficients = BiquadDesign.lowPass(top, BUTTERWORTH_Q, rate)
                        prepare(format)
                    }
                },
                maxOrder = min(HarmonicSettings.MAX_ORDER, floor(rate * NYQUIST_MARGIN / top).toInt()),
                channels = format.channels,
                // Long enough to average over a few cycles of the band's lowest frequency.
                follow = 1.0 - exp(-1.0 / (max(FOLLOW_MIN_SECONDS, FOLLOW_CYCLES / from) * rate)),
            )
        }
        scratch = AudioBuffer(format.channels, format.maxFrames)
        block = exp(-2.0 * Math.PI * DC_BLOCK_HZ / rate)
        rampFrames = (RAMP_SECONDS * rate).toInt().coerceAtLeast(1)
        // Start at the target: a processor made while music plays starts as it means to go on.
        bands.forEachIndexed { b, band -> load(band, b, target) }
        rampLeft = 0
    }

    /** Glides to [drive]'s gains. Call from the thread that calls [process]. */
    public fun update(drive: HarmonicDrive) {
        target = drive
        if (bands.isEmpty()) return
        bands.forEachIndexed { b, band ->
            val bandGain = drive.bandGains.getOrElse(b) { 1.0 }
            for (n in 2..HarmonicSettings.MAX_ORDER) {
                val wanted = if (n <= band.maxOrder) drive.gains.getOrElse(n) { 0.0 } * bandGain else 0.0
                band.steps[n] = (wanted - band.gains[n]) / rampFrames
            }
        }
        rampLeft = rampFrames
    }

    /** True once every gain has reached zero: there is nothing to add, and [process] costs nothing. */
    public val idle: Boolean get() = rampLeft == 0 && bands.all { band -> band.gains.all { it == 0.0 } }

    override fun process(buffer: AudioBuffer) {
        if (bands.isEmpty() || idle) return
        if (buffer.channels != scratch.channels) return
        // A block larger than promised costs one allocation rather than a dropped effect.
        if (buffer.frames > scratch.capacity) scratch = AudioBuffer(buffer.channels, buffer.frames)
        val frames = buffer.frames
        val ramp = min(rampLeft, frames)

        for (band in bands) {
            scratch.frames = frames
            for (ch in 0 until buffer.channels) System.arraycopy(buffer.data[ch], 0, scratch.data[ch], 0, frames)
            band.highPass.process(scratch)
            for (stage in band.lowPass) stage.process(scratch)

            val top = band.maxOrder
            val gains = band.gains
            val steps = band.steps
            for (ch in 0 until buffer.channels) {
                // Each channel walks the same ramp; restart it from where the block began.
                if (ch > 0 && ramp > 0) for (n in 2..top) gains[n] -= steps[n] * ramp
                val source = scratch.data[ch]
                val out = buffer.data[ch]
                var power = band.power[ch]
                var smoothed = band.smoothed[ch]
                var blockIn = band.blockIn[ch]
                var blockOut = band.blockOut[ch]
                for (i in 0 until frames) {
                    if (i < ramp) for (n in 2..top) gains[n] += steps[n]
                    val x = source[i].toDouble()
                    power += band.follow * (2.0 * x * x - power)
                    smoothed += band.follow * (power - smoothed)
                    val a2 = max(max(smoothed, x * x), TINY)
                    val a = sqrt(a2)
                    val y = x / a
                    var previous = 1.0
                    var current = y
                    var scale = a
                    var made = 0.0
                    for (n in 2..top) {
                        val next = 2.0 * y * current - previous
                        previous = current
                        current = next
                        scale *= a
                        made += gains[n] * scale * next
                    }
                    blockOut = made - blockIn + block * blockOut
                    blockIn = made
                    out[i] = (out[i] + blockOut).toFloat()
                }
                band.power[ch] = power
                band.smoothed[ch] = smoothed
                band.blockIn[ch] = blockIn
                band.blockOut[ch] = blockOut
            }
        }
        rampLeft -= ramp
        if (rampLeft == 0) bands.forEachIndexed { b, band -> load(band, b, target) }
    }

    override fun reset() {
        for (band in bands) {
            band.highPass.reset()
            band.lowPass.forEach(BiquadFilter::reset)
            band.power.fill(0.0)
            band.smoothed.fill(0.0)
            band.blockIn.fill(0.0)
            band.blockOut.fill(0.0)
        }
    }

    /** Sets [band]'s gains to [drive]'s exactly, ending any ramp's rounding. */
    private fun load(band: Band, index: Int, drive: HarmonicDrive) {
        val bandGain = drive.bandGains.getOrElse(index) { 1.0 }
        for (n in 0..HarmonicSettings.MAX_ORDER) {
            band.gains[n] = if (n in 2..band.maxOrder) drive.gains.getOrElse(n) { 0.0 } * bandGain else 0.0
            band.steps[n] = 0.0
        }
    }

    public companion object {
        /** Bass, midrange and treble, split where the ear hears warmth, body and edge. */
        public val BAND_EDGES: List<Pair<Double, Double>> = listOf(20.0 to 200.0, 200.0 to 2000.0, 2000.0 to 20000.0)
        public const val RAMP_SECONDS: Double = 0.03

        private const val BUTTERWORTH_Q = 0.7071
        /** A harmonic must land below this fraction of the sample rate, inside the Nyquist limit with room for the filter's skirt. */
        private const val NYQUIST_MARGIN = 0.45
        private const val FOLLOW_MIN_SECONDS = 0.01
        private const val FOLLOW_CYCLES = 1.5
        private const val DC_BLOCK_HZ = 10.0
        private const val TINY = 1e-18
    }
}
