package mmm.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** Normalised biquad coefficients (a0 divided out). */
public data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Magnitude response at [frequency] Hz, in dB, for a filter running at [sampleRate]. */
    public fun magnitudeDbAt(frequency: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequency / sampleRate
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2 * w)
        val s2w = sin(2 * w)
        val numRe = b0 + b1 * cw + b2 * c2w
        val numIm = -(b1 * sw + b2 * s2w)
        val denRe = 1.0 + a1 * cw + a2 * c2w
        val denIm = -(a1 * sw + a2 * s2w)
        val mag = hypot(numRe, numIm) / hypot(denRe, denIm)
        return 20.0 * kotlin.math.log10(mag.coerceAtLeast(1e-12))
    }

    public companion object {
        public val IDENTITY: BiquadCoefficients = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * RBJ audio-EQ-cookbook filter designs.
 *
 * These are the building blocks for every spectral artifact in the trainer: [peaking] gives Band ID
 * and resonance stimuli, [lowPass]/[highPass] give bandwidth limitation, and the shelves give
 * spectral-tilt questions.
 */
public object BiquadDesign {

    private const val MIN_Q = 0.05
    private const val MAX_Q = 200.0

    /** Clamps a centre frequency to something a digital biquad can actually realise. */
    private fun clampFreq(frequency: Double, sampleRate: Int): Double =
        frequency.coerceIn(1.0, sampleRate * 0.495)

    public fun peaking(frequency: Double, q: Double, gainDb: Double, sampleRate: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val f = clampFreq(frequency, sampleRate)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q.coerceIn(MIN_Q, MAX_Q))
        val a0 = 1 + alpha / a
        return BiquadCoefficients(
            b0 = (1 + alpha * a) / a0,
            b1 = (-2 * cos(w0)) / a0,
            b2 = (1 - alpha * a) / a0,
            a1 = (-2 * cos(w0)) / a0,
            a2 = (1 - alpha / a) / a0,
        )
    }

    public fun lowShelf(frequency: Double, slope: Double, gainDb: Double, sampleRate: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val f = clampFreq(frequency, sampleRate)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / slope.coerceIn(0.1, 2.0) - 1) + 2)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cw + twoSqrtAAlpha
        return BiquadCoefficients(
            b0 = a * ((a + 1) - (a - 1) * cw + twoSqrtAAlpha) / a0,
            b1 = 2 * a * ((a - 1) - (a + 1) * cw) / a0,
            b2 = a * ((a + 1) - (a - 1) * cw - twoSqrtAAlpha) / a0,
            a1 = -2 * ((a - 1) + (a + 1) * cw) / a0,
            a2 = ((a + 1) + (a - 1) * cw - twoSqrtAAlpha) / a0,
        )
    }

    public fun highShelf(frequency: Double, slope: Double, gainDb: Double, sampleRate: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val f = clampFreq(frequency, sampleRate)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / slope.coerceIn(0.1, 2.0) - 1) + 2)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cw + twoSqrtAAlpha
        return BiquadCoefficients(
            b0 = a * ((a + 1) + (a - 1) * cw + twoSqrtAAlpha) / a0,
            b1 = -2 * a * ((a - 1) + (a + 1) * cw) / a0,
            b2 = a * ((a + 1) + (a - 1) * cw - twoSqrtAAlpha) / a0,
            a1 = 2 * ((a - 1) - (a + 1) * cw) / a0,
            a2 = ((a + 1) - (a - 1) * cw - twoSqrtAAlpha) / a0,
        )
    }

    public fun lowPass(frequency: Double, q: Double, sampleRate: Int): BiquadCoefficients {
        val f = clampFreq(frequency, sampleRate)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceIn(MIN_Q, MAX_Q))
        val a0 = 1 + alpha
        return BiquadCoefficients(
            b0 = ((1 - cw) / 2) / a0,
            b1 = (1 - cw) / a0,
            b2 = ((1 - cw) / 2) / a0,
            a1 = (-2 * cw) / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    public fun highPass(frequency: Double, q: Double, sampleRate: Int): BiquadCoefficients {
        val f = clampFreq(frequency, sampleRate)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceIn(MIN_Q, MAX_Q))
        val a0 = 1 + alpha
        return BiquadCoefficients(
            b0 = ((1 + cw) / 2) / a0,
            b1 = (-(1 + cw)) / a0,
            b2 = ((1 + cw) / 2) / a0,
            a1 = (-2 * cw) / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    /** Band-pass with constant 0 dB peak gain. [bandwidthOctaves] sets the skirt width. */
    public fun bandPass(frequency: Double, bandwidthOctaves: Double, sampleRate: Int): BiquadCoefficients {
        val f = clampFreq(frequency, sampleRate)
        val w0 = 2.0 * PI * f / sampleRate
        val bw = bandwidthOctaves.coerceIn(0.01, 6.0)
        val alpha = sin(w0) * sinh(ln(2.0) / 2.0 * bw * w0 / sin(w0))
        val a0 = 1 + alpha
        return BiquadCoefficients(
            b0 = alpha / a0,
            b1 = 0.0,
            b2 = (-alpha) / a0,
            a1 = (-2 * cos(w0)) / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    public fun notch(frequency: Double, q: Double, sampleRate: Int): BiquadCoefficients {
        val f = clampFreq(frequency, sampleRate)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q.coerceIn(MIN_Q, MAX_Q))
        val a0 = 1 + alpha
        return BiquadCoefficients(
            b0 = 1.0 / a0,
            b1 = (-2 * cos(w0)) / a0,
            b2 = 1.0 / a0,
            a1 = (-2 * cos(w0)) / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    /** Converts an octave bandwidth to the equivalent Q for [peaking]. */
    public fun qForBandwidthOctaves(bandwidthOctaves: Double): Double {
        val bw = bandwidthOctaves.coerceIn(0.01, 6.0)
        val p = 2.0.pow(bw)
        return sqrt(p) / (p - 1.0)
    }
}

/**
 * Stateful multi-channel biquad, transposed direct form II (good numerical behaviour at the low
 * centre frequencies the bass-band exercises need).
 */
public class BiquadFilter(channels: Int = 2) : AudioProcessor {

    private var z1 = DoubleArray(channels)
    private var z2 = DoubleArray(channels)

    public var coefficients: BiquadCoefficients = BiquadCoefficients.IDENTITY

    override fun prepare(format: AudioFormat) {
        if (z1.size != format.channels) {
            z1 = DoubleArray(format.channels)
            z2 = DoubleArray(format.channels)
        }
        reset()
    }

    override fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    override fun process(buffer: AudioBuffer) {
        val c = coefficients
        if (c === BiquadCoefficients.IDENTITY) return
        val b0 = c.b0; val b1 = c.b1; val b2 = c.b2; val a1 = c.a1; val a2 = c.a2
        for (ch in 0 until buffer.channels) {
            val samples = buffer.data[ch]
            var s1 = z1[ch]
            var s2 = z2[ch]
            for (i in 0 until buffer.frames) {
                val x = samples[i].toDouble()
                val y = b0 * x + s1
                s1 = b1 * x - a1 * y + s2
                s2 = b2 * x - a2 * y
                samples[i] = y.toFloat()
            }
            z1[ch] = s1
            z2[ch] = s2
        }
    }
}

/** A series of [BiquadFilter]s, used for steeper bandwidth slopes and multi-band EQ curves. */
public class BiquadCascade(private val filters: List<BiquadFilter>) : AudioProcessor {

    public constructor(stageCount: Int, channels: Int) :
        this(List(stageCount) { BiquadFilter(channels) })

    public val stages: List<BiquadFilter> get() = filters

    public fun setCoefficients(index: Int, coefficients: BiquadCoefficients) {
        filters[index].coefficients = coefficients
    }

    override fun prepare(format: AudioFormat): Unit = filters.forEach { it.prepare(format) }
    override fun process(buffer: AudioBuffer): Unit = filters.forEach { it.process(buffer) }
    override fun reset(): Unit = filters.forEach(BiquadFilter::reset)
}
