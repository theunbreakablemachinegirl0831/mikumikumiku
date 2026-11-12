package mmm.dsp

import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Frequency weightings for display: how loud each part of the spectrum is to a listener rather
 * than to a microphone. None of this touches audio; the spectrum displays apply it to what they
 * draw.
 */
public object FrequencyWeighting {

    /**
     * A-weighting, in dB, as IEC 61672-1 defines it: the analogue filter's response, normalised
     * to 0 dB at 1 kHz. About -19 dB at 100 Hz, +1 dB at 2.5 kHz, -9 dB at 20 kHz.
     */
    public fun aWeightingDb(frequency: Double): Double {
        val f2 = frequency * frequency
        val ra = (12194.0 * 12194.0 * f2 * f2) /
            ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
        return 20 * log10(ra.coerceAtLeast(1e-30)) + A_NORMALISATION_DB
    }

    /**
     * The sound pressure level, in dB SPL, of a tone at [frequency] heard as loud as a 1 kHz tone
     * at [phon] - the ISO 226:2003 equal-loudness contour. Computed exactly at the standard's 29
     * frequencies (20 Hz to 12.5 kHz) from its formula and table, and between them by monotone
     * cubic interpolation in log frequency; outside that range it is held at the end value, which
     * the standard does not define.
     *
     * The standard's formula holds from 20 to 90 phon (to 80 above 4 kHz). A level here is a
     * choice of curve, not a measurement: the app does not know how loud anything actually is at
     * the eardrum.
     */
    public fun equalLoudnessSpl(frequency: Double, phon: Double): Double {
        val points = contour(phon)
        val x = ln(frequency.coerceIn(ISO_F.first(), ISO_F.last()))
        return points.at(x)
    }

    /**
     * How much louder or quieter a component at [frequency] sounds than a 1 kHz one at the same
     * level, on the [phon] contour: 0 at 1 kHz, negative where the ear is less sensitive.
     */
    public fun equalLoudnessWeightDb(frequency: Double, phon: Double): Double =
        equalLoudnessSpl(1000.0, phon) - equalLoudnessSpl(frequency, phon)

    /** The contour's exact values at the standard's frequencies, by the ISO 226:2003 formula. */
    public fun contourAtTable(phon: Double): DoubleArray = DoubleArray(ISO_F.size) { i ->
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) +
            (0.4 * 10.0.pow((ISO_TF[i] + ISO_LU[i]) / 10 - 9)).pow(ISO_AF[i])
        10 / ISO_AF[i] * log10(af) - ISO_LU[i] + 94
    }

    private val contours = HashMap<Double, Pchip>()

    private fun contour(phon: Double): Pchip = synchronized(contours) {
        contours.getOrPut(phon) { Pchip(ISO_F.map { ln(it) }.toDoubleArray(), contourAtTable(phon)) }
    }

    private const val A_NORMALISATION_DB = 2.0

    /** ISO 226:2003 Table 1. */
    public val ISO_F: DoubleArray = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
        630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
    )
    private val ISO_AF = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267,
        0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301,
    )
    private val ISO_LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0,
        0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1,
    )
    private val ISO_TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4,
        3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )

    /**
     * Monotone piecewise-cubic interpolation (Fritsch-Carlson): smooth through every point, and
     * never overshooting between two of them, which a contour's sharp dip near 4 kHz would.
     */
    private class Pchip(private val x: DoubleArray, private val y: DoubleArray) {
        private val m = DoubleArray(x.size)

        init {
            val n = x.size
            val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else {
                    val w1 = 2 * (x[i + 1] - x[i]) + (x[i] - x[i - 1])
                    val w2 = (x[i + 1] - x[i]) + 2 * (x[i] - x[i - 1])
                    (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
        }

        fun at(t: Double): Double {
            if (t <= x.first()) return y.first()
            if (t >= x.last()) return y.last()
            var lo = 0
            var hi = x.lastIndex
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (x[mid] <= t) lo = mid else hi = mid
            }
            val h = x[hi] - x[lo]
            val s = (t - x[lo]) / h
            val s2 = s * s
            val s3 = s2 * s
            return (2 * s3 - 3 * s2 + 1) * y[lo] + (s3 - 2 * s2 + s) * h * m[lo] +
                (-2 * s3 + 3 * s2) * y[hi] + (s3 - s2) * h * m[hi]
        }
    }
}
