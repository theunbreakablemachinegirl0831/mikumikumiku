package mmm.eq

import kotlin.math.ln
import kotlin.math.pow

/**
 * A magnitude response: dB against frequency, sorted by frequency.
 *
 * Everything here works on a logarithmic frequency axis, because that is how hearing and every
 * published measurement treat frequency: an octave is the same distance at 100 Hz as at 10 kHz.
 */
public class FrequencyResponse(frequencies: List<Double>, decibels: List<Double>) {

    public val frequencies: List<Double>
    public val decibels: List<Double>

    init {
        require(frequencies.size == decibels.size) { "frequency and level counts differ" }
        require(frequencies.isNotEmpty()) { "a response needs at least one point" }
        val sorted = frequencies.indices
            .filter { frequencies[it] > 0.0 && decibels[it].isFinite() }
            .sortedBy { frequencies[it] }
            .distinctBy { frequencies[it] }
        require(sorted.isNotEmpty()) { "no usable points" }
        this.frequencies = sorted.map { frequencies[it] }
        this.decibels = sorted.map { decibels[it] }
    }

    public val size: Int get() = frequencies.size

    /** Level at [frequency], interpolated linearly in log frequency and held flat past either end. */
    public fun at(frequency: Double): Double {
        if (frequency <= frequencies.first()) return decibels.first()
        if (frequency >= frequencies.last()) return decibels.last()
        var low = 0
        var high = frequencies.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (frequencies[mid] <= frequency) low = mid else high = mid
        }
        val x0 = ln(frequencies[low])
        val x1 = ln(frequencies[high])
        val t = (ln(frequency) - x0) / (x1 - x0)
        return decibels[low] + t * (decibels[high] - decibels[low])
    }

    /** This response sampled on [grid]. */
    public fun resample(grid: List<Double>): FrequencyResponse = FrequencyResponse(grid, grid.map(::at))

    /**
     * Fractional-octave smoothing: each point becomes the mean level within 1/[fraction] of an
     * octave around it. 1/12 keeps the shape an earphone's tuning is judged by while flattening
     * the comb of a raw measurement; 1/3 approaches what a single EQ band can actually follow.
     */
    public fun smooth(fraction: Int): FrequencyResponse {
        require(fraction > 0) { "fraction must be positive" }
        val grid = logGrid(frequencies.first(), frequencies.last(), POINTS_PER_OCTAVE)
        val levels = grid.map(::at)
        val halfWidth = POINTS_PER_OCTAVE / (2.0 * fraction)
        val smoothed = grid.indices.map { i ->
            val from = (i - halfWidth).toInt().coerceAtLeast(0)
            val to = (i + halfWidth).toInt().coerceAtMost(grid.lastIndex)
            var sum = 0.0
            for (j in from..to) sum += levels[j]
            sum / (to - from + 1)
        }
        return FrequencyResponse(grid, smoothed)
    }

    /** Shifted so its mean level between [fromHz] and [toHz] is 0 dB. */
    public fun normalized(fromHz: Double = 500.0, toHz: Double = 1000.0): FrequencyResponse {
        val grid = logGrid(fromHz, toHz, POINTS_PER_OCTAVE)
        val mean = grid.sumOf(::at) / grid.size
        return shifted(-mean)
    }

    public fun shifted(db: Double): FrequencyResponse = FrequencyResponse(frequencies, decibels.map { it + db })

    /**
     * Adds a straight line of [dbPerOctave] through 0 dB at [pivotHz]. Negative tilts darken -
     * the squig.link tools offer -1 dB/oct as a default on neutral targets.
     */
    public fun tilted(dbPerOctave: Double, pivotHz: Double = 1000.0): FrequencyResponse =
        FrequencyResponse(frequencies, frequencies.indices.map { i ->
            decibels[i] + dbPerOctave * log2(frequencies[i] / pivotHz)
        })

    /** Point by point difference on this response's frequencies. */
    public operator fun minus(other: FrequencyResponse): FrequencyResponse =
        FrequencyResponse(frequencies, frequencies.indices.map { decibels[it] - other.at(frequencies[it]) })

    public operator fun plus(other: FrequencyResponse): FrequencyResponse =
        FrequencyResponse(frequencies, frequencies.indices.map { decibels[it] + other.at(frequencies[it]) })

    public companion object {
        /** Grid density used for smoothing and fitting: fine enough for 1/24-octave data. */
        public const val POINTS_PER_OCTAVE: Int = 48

        /** Frequencies from [from] to [to] Hz, evenly spaced in log frequency. */
        public fun logGrid(from: Double = 20.0, to: Double = 20000.0, pointsPerOctave: Int = POINTS_PER_OCTAVE): List<Double> {
            val octaves = log2(to / from)
            val count = (octaves * pointsPerOctave).toInt().coerceAtLeast(1)
            return (0..count).map { from * 2.0.pow(it * octaves / count) }
        }

        /** The mean of several responses (left and right channels, say), on the first one's grid. */
        public fun average(responses: List<FrequencyResponse>): FrequencyResponse {
            require(responses.isNotEmpty()) { "nothing to average" }
            val grid = responses.first().frequencies
            return FrequencyResponse(grid, grid.map { f -> responses.sumOf { it.at(f) } / responses.size })
        }

        /**
         * Reads a measurement or target as squig.link, REW and AutoEq export them: one point per
         * line, frequency then level, separated by whitespace, a comma, a tab or a semicolon.
         * Header and comment lines - anything that does not start with two numbers - are skipped,
         * which covers REW's "Smoothed Frequency Response" preamble and CSV column names alike.
         */
        public fun parse(text: String): FrequencyResponse {
            val frequencies = ArrayList<Double>()
            val levels = ArrayList<Double>()
            for (line in text.lineSequence()) {
                val fields = line.trim().split(SEPARATORS).filter { it.isNotEmpty() }
                if (fields.size < 2) continue
                val frequency = fields[0].toDoubleOrNull() ?: continue
                val level = fields[1].toDoubleOrNull() ?: continue
                if (frequency <= 0.0 || !level.isFinite()) continue
                frequencies += frequency
                levels += level
            }
            require(frequencies.size >= 2) { "주파수/레벨 쌍을 찾지 못했다" }
            return FrequencyResponse(frequencies, levels)
        }

        private val SEPARATORS = Regex("[\\s,;]+")
    }
}

internal fun log2(x: Double): Double = ln(x) / ln(2.0)
