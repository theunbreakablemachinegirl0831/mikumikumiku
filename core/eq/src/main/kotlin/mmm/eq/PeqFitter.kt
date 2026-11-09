package mmm.eq

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Limits on what the automatic EQ may do. */
public data class PeqFitSettings(
    val maxFilters: Int = 10,
    /** Boost per filter. Boosting is what costs headroom and what can hurt, so it is held tighter than cutting. */
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    /**
     * Boost of all filters together at any one frequency. Without it overlapping filters can stack
     * past the per-filter limit - in the full-range mode, several at once chasing an earphone's
     * natural roll-off above 17 kHz - and the preamp then has to take the whole EQ down to match.
     */
    val maxTotalBoostDb: Double = 8.0,
    val minQ: Double = 0.5,
    val maxQ: Double = 6.0,
    /** Shelves use the Q AutoEq uses, a gentle 0.7, so they move the balance without a bump. */
    val shelfQ: Double = 0.7,
    val fitFromHz: Double = 20.0,
    /**
     * Above this, only a broad high shelf follows the trend. A measurement rig's ear is not the
     * listener's ear, and past roughly 8-10 kHz the narrow peaks and dips of an in-ear response
     * move with insertion depth and ear canal shape - chasing them on one person's ear corrects
     * features that are not there. [FULL_RANGE] lifts the limit for listeners who want it anyway.
     */
    val detailLimitHz: Double = 10000.0,
    val smoothingFraction: Int = 12,
) {
    val fullRange: Boolean get() = detailLimitHz >= FULL_RANGE_HZ

    public companion object {
        public const val FULL_RANGE_HZ: Double = 20000.0
        public val DEFAULT: PeqFitSettings = PeqFitSettings()
        public val FULL_RANGE: PeqFitSettings = PeqFitSettings(detailLimitHz = FULL_RANGE_HZ)
    }
}

public data class PeqFitResult(
    val profile: EqProfile,
    /** What the EQ was asked to do: target minus measurement, centred on the midrange. */
    val wanted: FrequencyResponse,
    /** The measurement as it should sound with the EQ, normalised like the target. */
    val predicted: FrequencyResponse,
    /** RMS of [wanted] over the detailed range before EQ, in dB. */
    val rmsBeforeDb: Double,
    /** RMS left after EQ over the same range, in dB. */
    val rmsAfterDb: Double,
)

/**
 * Fits a parametric EQ that moves a measured response onto a target.
 *
 * Greedy then refine, the same shape as AutoEq's approach: shelves take the broad bass and treble
 * trend, then peaking filters are added one at a time at the largest remaining error, each sized
 * from the width of the error it is aimed at, and after every addition all filters are refined
 * together by coordinate descent on the weighted squared error. Coordinate descent is crude but
 * has no tuning to get wrong, and ten filters are few enough for it to be fast.
 */
public class PeqFitter(private val settings: PeqFitSettings = PeqFitSettings.DEFAULT) {

    private val grid = FrequencyResponse.logGrid(20.0, 20000.0, POINTS_PER_OCTAVE)
    private val n = grid.size

    public fun fit(measured: FrequencyResponse, target: FrequencyResponse): PeqFitResult {
        val fraction = settings.smoothingFraction
        val raw = (target.smooth(fraction) - measured.smooth(fraction)).resample(grid)
        val wanted = raw.shifted(-meanOver(raw, CENTRE_FROM_HZ, CENTRE_TO_HZ))

        // Above the detail limit the fitter only sees the octave-smoothed trend, which a shelf can follow.
        val trend = wanted.smooth(1)
        val goal = DoubleArray(n) { i -> if (grid[i] <= settings.detailLimitHz) wanted.decibels[i] else trend.at(grid[i]) }
        val weight = DoubleArray(n) { i ->
            val f = grid[i]
            when {
                f < settings.fitFromHz -> 0.0
                f <= settings.detailLimitHz -> 1.0
                f <= TREBLE_TREND_TO_HZ -> TREBLE_TREND_WEIGHT
                else -> 0.0
            }
        }

        val state = FitState(goal, weight)
        state.add(Band(EqFilterType.LOW_SHELF, LOW_SHELF_HZ, settings.shelfQ, clampGain(meanOf(goal, 20.0, 60.0))))
        if (!settings.fullRange) {
            state.add(
                Band(
                    EqFilterType.HIGH_SHELF,
                    settings.detailLimitHz,
                    settings.shelfQ,
                    clampGain(meanOf(goal, settings.detailLimitHz, TREBLE_TREND_TO_HZ)),
                )
            )
        }
        state.refine(QUICK_SWEEPS)

        while (state.bands.size < settings.maxFilters) {
            val next = nextPeak(state) ?: break
            state.add(next)
            state.refine(QUICK_SWEEPS)
        }
        state.refine(FINAL_SWEEPS)
        state.bands.removeAll { abs(it.gainDb) < NEGLIGIBLE_DB }

        val filters = state.bands
            .sortedWith(compareBy({ it.type != EqFilterType.LOW_SHELF }, { it.type == EqFilterType.HIGH_SHELF }, { it.frequency }))
            .map { EqFilter(it.type, it.frequency, it.q, it.gainDb) }
        val unscaled = EqProfile(filters, 0.0)
        val peak = (0 until n).maxOf { unscaled.magnitudeDbAt(grid[it]) }
        val profile = unscaled.copy(preampDb = if (peak > 0.0) -(peak + HEADROOM_DB) else 0.0)

        val applied = unscaled.response(grid)
        val residual = FrequencyResponse(grid, (0 until n).map { wanted.decibels[it] - applied.decibels[it] })
        val predicted = (measured.smooth(fraction).resample(grid) + applied).normalized()

        return PeqFitResult(
            profile = profile,
            wanted = wanted,
            predicted = predicted,
            rmsBeforeDb = rms(wanted.decibels, detailedOnly = true),
            rmsAfterDb = rms(residual.decibels, detailedOnly = true),
        )
    }

    /** A peak aimed at the largest remaining error, or null when nothing is left worth a filter. */
    private fun nextPeak(state: FitState): Band? {
        val residual = state.residual()
        val applied = state.applied()
        val limit = if (settings.fullRange) 20000.0 else settings.detailLimitHz
        var best = -1
        for (i in 0 until n) {
            if (grid[i] < PEAK_FROM_HZ || grid[i] > limit) continue
            // Where the boost is already at its ceiling another boost cannot help; aiming there
            // would only spend filters on an error that has to stay.
            if (residual[i] > 0 && applied[i] >= settings.maxTotalBoostDb - STOP_DB) continue
            if (best < 0 || abs(residual[i]) > abs(residual[best])) best = i
        }
        if (best < 0 || abs(residual[best]) < STOP_DB) return null

        // Width of the error at half its height sets the Q.
        val height = residual[best]
        var left = best
        while (left > 0 && residual[left - 1] * height > 0 && abs(residual[left - 1]) > abs(height) / 2) left--
        var right = best
        while (right < n - 1 && residual[right + 1] * height > 0 && abs(residual[right + 1]) > abs(height) / 2) right++
        val octaves = ((right - left + 1).toDouble() / POINTS_PER_OCTAVE).coerceAtLeast(MIN_WIDTH_OCTAVES)
        val ratio = 2.0.pow(octaves)
        val q = (sqrt(ratio) / (ratio - 1)).coerceIn(settings.minQ, settings.maxQ)

        return Band(EqFilterType.PEAK, grid[best], q, clampGain(height))
    }

    private fun clampGain(db: Double): Double = db.coerceIn(-settings.maxCutDb, settings.maxBoostDb)

    private fun meanOf(values: DoubleArray, fromHz: Double, toHz: Double): Double {
        var sum = 0.0
        var count = 0
        for (i in 0 until n) if (grid[i] in fromHz..toHz) { sum += values[i]; count++ }
        return if (count == 0) 0.0 else sum / count
    }

    private fun meanOver(response: FrequencyResponse, fromHz: Double, toHz: Double): Double =
        meanOf(response.decibels.toDoubleArray(), fromHz, toHz)

    private fun rms(values: List<Double>, detailedOnly: Boolean): Double {
        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            val f = grid[i]
            if (f < settings.fitFromHz) continue
            if (detailedOnly && f > settings.detailLimitHz) continue
            sum += values[i] * values[i]
            count++
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private class Band(val type: EqFilterType, var frequency: Double, var q: Double, var gainDb: Double)

    /** The bands being fitted, with each band's response cached so a tweak recomputes only that band. */
    private inner class FitState(private val goal: DoubleArray, private val weight: DoubleArray) {
        val bands = mutableListOf<Band>()
        private val responses = mutableListOf<DoubleArray>()
        private val weightSum = weight.sum().coerceAtLeast(1e-9)

        fun add(band: Band) {
            bands += band
            responses += response(band)
        }

        fun applied(): DoubleArray = DoubleArray(n) { i -> responses.sumOf { it[i] } }

        fun residual(): DoubleArray = DoubleArray(n) { i -> goal[i] - responses.sumOf { it[i] } }

        private fun response(band: Band): DoubleArray {
            val filter = EqFilter(band.type, band.frequency, band.q, band.gainDb)
            val coefficients = filter.coefficients(EqFilter.DESIGN_RATE)
            return DoubleArray(n) { i -> coefficients.magnitudeDbAt(grid[i], EqFilter.DESIGN_RATE) }
        }

        private fun loss(): Double {
            var sum = 0.0
            for (i in 0 until n) {
                var total = 0.0
                for (r in responses) total += r[i]
                val excess = total - settings.maxTotalBoostDb
                if (excess > 0) sum += OVER_BOOST_PENALTY * excess * excess
                if (weight[i] == 0.0) continue
                val e = goal[i] - total
                sum += weight[i] * e * e
            }
            return sum / weightSum
        }

        /** Coordinate descent over every band's frequency, Q and gain, within the settings' limits. */
        fun refine(sweeps: Int) {
            var frequencyStep = 0.25 // octaves
            var qStep = 0.25 // log2 units
            var gainStep = 1.0 // dB
            var current = loss()
            repeat(sweeps) {
                var improved = false
                for (index in bands.indices) {
                    val band = bands[index]
                    for (parameter in 0..2) {
                        if (parameter == 1 && band.type != EqFilterType.PEAK) continue
                        for (direction in intArrayOf(1, -1)) {
                            val saved = Triple(band.frequency, band.q, band.gainDb)
                            when (parameter) {
                                0 -> band.frequency = clampFrequency(band, band.frequency * 2.0.pow(direction * frequencyStep))
                                1 -> band.q = (band.q * 2.0.pow(direction * qStep)).coerceIn(settings.minQ, settings.maxQ)
                                else -> band.gainDb = clampGain(band.gainDb + direction * gainStep)
                            }
                            val savedResponse = responses[index]
                            responses[index] = response(band)
                            val candidate = loss()
                            if (candidate < current - 1e-9) {
                                current = candidate
                                improved = true
                            } else {
                                band.frequency = saved.first
                                band.q = saved.second
                                band.gainDb = saved.third
                                responses[index] = savedResponse
                            }
                        }
                    }
                }
                if (!improved) {
                    frequencyStep /= 2
                    qStep /= 2
                    gainStep /= 2
                    if (gainStep < 0.05) return
                }
            }
        }

        private fun clampFrequency(band: Band, frequency: Double): Double = when (band.type) {
            EqFilterType.LOW_SHELF -> frequency.coerceIn(20.0, 400.0)
            EqFilterType.HIGH_SHELF -> frequency.coerceIn(settings.detailLimitHz / 2, TREBLE_TREND_TO_HZ)
            EqFilterType.PEAK -> frequency.coerceIn(
                PEAK_FROM_HZ,
                if (settings.fullRange) 20000.0 else settings.detailLimitHz,
            )
        }
    }

    private companion object {
        const val POINTS_PER_OCTAVE = 24
        const val CENTRE_FROM_HZ = 250.0
        const val CENTRE_TO_HZ = 2000.0
        const val LOW_SHELF_HZ = 105.0
        const val TREBLE_TREND_TO_HZ = 16000.0
        const val TREBLE_TREND_WEIGHT = 0.5
        /** Remaining error below this is left alone: a filter for it would be fitting the rig, not the earphone. */
        const val STOP_DB = 1.0
        const val NEGLIGIBLE_DB = 0.5

        /** The bottom octave is the low shelf's; a peak there mostly argues with it. */
        const val PEAK_FROM_HZ = 40.0
        const val OVER_BOOST_PENALTY = 50.0
        const val MIN_WIDTH_OCTAVES = 1.0 / 6
        const val HEADROOM_DB = 0.5
        const val QUICK_SWEEPS = 12
        const val FINAL_SWEEPS = 60
    }
}
