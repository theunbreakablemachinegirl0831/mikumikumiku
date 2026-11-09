package mmm.eq

import kotlin.math.sqrt

/** One band of a band-gain equaliser: everything from the previous band's cutoff up to [cutoffHz] gets [gainDb]. */
public data class GraphicBand(val cutoffHz: Double, val gainDb: Double)

/**
 * An [EqProfile] re-expressed for an equaliser that takes a gain per band rather than filters -
 * Android's DynamicsProcessing, which is how the EQ reaches another app's audio (Spotify's)
 * without capturing it.
 *
 * Such an equaliser applies a gain to every frequency between two cutoffs, so the PEQ's curve is
 * sampled at each band's centre, on a log grid fine enough that the steps between bands stay well
 * under a decibel. The preamp is kept apart, because the effect has its own input gain.
 */
public class GraphicEqBands private constructor(
    public val bands: List<GraphicBand>,
    public val preampDb: Double,
) {
    /**
     * The response the band equaliser is expected to produce, assuming each band's gain holds
     * across the band. How a device blends neighbouring bands is up to its implementation, so
     * this is an estimate - the comparison drawn on screen, not a measurement.
     */
    public fun realizedDbAt(frequency: Double): Double {
        val band = bands.firstOrNull { frequency <= it.cutoffHz } ?: bands.last()
        return preampDb + band.gainDb
    }

    public fun realizedResponse(grid: List<Double> = FrequencyResponse.logGrid()): FrequencyResponse =
        FrequencyResponse(grid, grid.map(::realizedDbAt))

    public companion object {
        public const val DEFAULT_BANDS_PER_OCTAVE: Int = 24

        /**
         * @param bandsPerOctave grid density; 24 keeps the step between bands under about half a
         *   decibel even across the narrowest filter the fitter makes (Q 6)
         */
        public fun from(
            profile: EqProfile,
            bandsPerOctave: Int = DEFAULT_BANDS_PER_OCTAVE,
            fromHz: Double = 20.0,
            toHz: Double = 20000.0,
        ): GraphicEqBands {
            require(bandsPerOctave > 0) { "need at least one band per octave" }
            val centres = FrequencyResponse.logGrid(fromHz, toHz, bandsPerOctave)
            val bands = centres.mapIndexed { i, centre ->
                // The last band runs to the top of the spectrum.
                val cutoff = if (i == centres.lastIndex) MAX_CUTOFF_HZ else sqrt(centre * centres[i + 1])
                GraphicBand(cutoff, profile.magnitudeDbAt(centre) - profile.preampDb)
            }
            return GraphicEqBands(bands, profile.preampDb)
        }

        private const val MAX_CUTOFF_HZ = 24000.0
    }
}
