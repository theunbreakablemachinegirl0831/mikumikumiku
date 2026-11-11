package mmm.eq

import kotlin.math.abs

/**
 * A graphic equaliser: fixed centre frequencies, one gain each.
 *
 * Played as peaking filters of constant Q, like the hardware it is named after. Neighbouring
 * bands of such an equaliser overlap, so ten sliders at +6 dB would give a hump well above +6 dB
 * rather than a +6 dB line; the gains are therefore solved for, so that the response passes
 * through what each slider shows at its own centre. What is drawn on the sliders is what plays.
 */
public class GraphicEq private constructor(
    public val centres: List<Double>,
    public val q: Double,
) {
    public val size: Int get() = centres.size

    /** Response in dB of a 1 dB boost of band `j`, sampled at band `i`'s centre. */
    private val unit: Array<DoubleArray> by lazy {
        Array(size) { i ->
            DoubleArray(size) { j -> EqFilter(EqFilterType.PEAK, centres[j], q, 1.0).magnitudeDbAt(centres[i]) }
        }
    }
    private val inverse: Array<DoubleArray> by lazy { invert(unit) }

    /**
     * The filters that make the response pass through [sliderDb] at the centres.
     * @param sliderDb one gain per band; missing bands count as 0
     */
    public fun filters(sliderDb: List<Double>): List<EqFilter> {
        val wanted = DoubleArray(size) { sliderDb.getOrElse(it) { 0.0 } }
        if (wanted.all { abs(it) < 1e-6 }) return emptyList()
        // A filter's response in dB is close to proportional to its gain in dB, so the linear
        // solve lands near the answer and a few corrections against the real response finish it.
        val gains = multiply(inverse, wanted)
        repeat(CORRECTIONS) {
            val actual = responseAtCentres(gains)
            val error = DoubleArray(size) { wanted[it] - actual[it] }
            val step = multiply(inverse, error)
            for (i in 0 until size) gains[i] = (gains[i] + step[i]).coerceIn(-MAX_FILTER_DB, MAX_FILTER_DB)
        }
        return centres.indices.map { EqFilter(EqFilterType.PEAK, centres[it], q, gains[it]) }
    }

    private fun responseAtCentres(gains: DoubleArray): DoubleArray {
        val designed = centres.indices.map { EqFilter(EqFilterType.PEAK, centres[it], q, gains[it]).coefficients(EqFilter.DESIGN_RATE) }
        return DoubleArray(size) { i -> designed.sumOf { it.magnitudeDbAt(centres[i], EqFilter.DESIGN_RATE) } }
    }

    public companion object {
        /** The ten octave bands of a consumer equaliser. */
        public val OCTAVE: GraphicEq = GraphicEq(
            listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0),
            q = 1.414,
        )

        /** The thirty-one third-octave bands of a studio equaliser, at their nominal frequencies. */
        public val THIRD_OCTAVE: GraphicEq = GraphicEq(
            listOf(
                20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
                800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
                16000.0, 20000.0,
            ),
            q = 4.318,
        )

        /** The layout with [bands] sliders: 31 gives third octaves, anything else octaves. */
        public fun of(bands: Int): GraphicEq = if (bands == THIRD_OCTAVE.size) THIRD_OCTAVE else OCTAVE

        private const val CORRECTIONS = 4
        private const val MAX_FILTER_DB = 24.0

        private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
            DoubleArray(matrix.size) { i -> matrix[i].indices.sumOf { j -> matrix[i][j] * vector[j] } }

        /** Gauss-Jordan with partial pivoting; the matrix is diagonally dominant, so it is well behaved. */
        private fun invert(source: Array<DoubleArray>): Array<DoubleArray> {
            val n = source.size
            val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) source[i][j] else if (j - n == i) 1.0 else 0.0 } }
            for (column in 0 until n) {
                var pivot = column
                for (row in column + 1 until n) if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
                val swap = a[column]; a[column] = a[pivot]; a[pivot] = swap
                val divisor = a[column][column]
                require(abs(divisor) > 1e-12) { "band responses are not independent" }
                for (j in 0 until 2 * n) a[column][j] /= divisor
                for (row in 0 until n) {
                    if (row == column) continue
                    val factor = a[row][column]
                    if (factor == 0.0) continue
                    for (j in 0 until 2 * n) a[row][j] -= factor * a[column][j]
                }
            }
            return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
        }
    }
}
