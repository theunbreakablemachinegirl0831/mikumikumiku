package mmm.training

/**
 * Listener-chosen filter settings that replace the difficulty ladder's own.
 *
 * The ladder starts loud on purpose - a big boost is easy to place - but a +12 dB peak is also
 * genuinely unpleasant to sit through, and how much is too much is the listener's call, not the
 * ladder's. When set, these replace the ladder's gain and Q for every level, so climbing the
 * ladder then only changes what else gets harder (a finer grid, more alternatives).
 *
 * Applied at generation rather than patched onto a finished question, so the explanation shown
 * afterwards describes the filter that was actually played.
 */
public data class FilterOverrides(
    /** Boost size in dB, always positive; cut questions keep their sign. Null keeps the ladder's. */
    val gainDb: Double? = null,
    /** Filter Q. Null keeps the ladder's (for Band ID, the band's own width). */
    val q: Double? = null,
) {
    init {
        require(gainDb == null || gainDb > 0.0) { "gainDb must be positive, was $gainDb" }
        require(q == null || q > 0.0) { "q must be positive, was $q" }
    }

    public val isActive: Boolean get() = gainDb != null || q != null

    public companion object {
        public val NONE: FilterOverrides = FilterOverrides()

        /** Which families these settings mean anything for. */
        public val APPLIES_TO: Set<ExerciseFamily> =
            setOf(ExerciseFamily.BAND_ID, ExerciseFamily.RESONANCE)
    }
}
