package mmm.eq

import kotlin.math.abs

/**
 * Several EQs played as one.
 *
 * The playback EQ is built in layers - the automatic earphone correction, the listener's own
 * parametric bands, the graphic sliders - and each is just a list of filters. Stacked, they are
 * one longer list; what has to be worked out afresh is the preamp, because headroom depends on
 * where the layers' boosts land on top of each other, not on any one of them.
 */
public object EqStack {

    /** Left under full scale after the loudest boost, as the fitter leaves. */
    public const val HEADROOM_DB: Double = 0.5

    private val GRID = FrequencyResponse.logGrid(20.0, 20000.0, 24)

    /**
     * @param manualPreampDb the listener's own gain, applied on top of the automatic one
     * @param autoHeadroom lower the preamp by the stack's largest boost, so it cannot clip
     * @return the stacked profile, or null when it would do nothing at all
     */
    public fun combine(
        layers: List<List<EqFilter>>,
        manualPreampDb: Double = 0.0,
        autoHeadroom: Boolean = true,
    ): EqProfile? {
        val filters = layers.flatten().filter { abs(it.gainDb) >= NEGLIGIBLE_DB }
        val headroom = if (autoHeadroom) -headroomFor(filters) else 0.0
        val preamp = manualPreampDb + headroom
        if (filters.isEmpty() && abs(preamp) < NEGLIGIBLE_DB) return null
        return EqProfile(filters, preamp)
    }

    /** How far [filters] together rise above 0 dB at their highest point, plus [HEADROOM_DB]; 0 if they never do. */
    public fun headroomFor(filters: List<EqFilter>): Double {
        if (filters.isEmpty()) return 0.0
        val designed = filters.map { it.coefficients(EqFilter.DESIGN_RATE) }
        val peak = GRID.maxOf { f -> designed.sumOf { it.magnitudeDbAt(f, EqFilter.DESIGN_RATE) } }
        return if (peak > 0.0) peak + HEADROOM_DB else 0.0
    }

    private const val NEGLIGIBLE_DB = 0.05
}
