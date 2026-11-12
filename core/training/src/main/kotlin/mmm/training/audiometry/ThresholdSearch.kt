package mmm.training.audiometry

/**
 * One threshold, found by the modified Hughson-Westlake procedure that clinical pure-tone
 * audiometry uses: down 10 dB after every tone that is heard, up 5 dB after every tone that is
 * not, and the threshold is the lowest level heard on two ascending presentations - a tone
 * reached by going up, which is the direction a listener has no expectation about.
 *
 * Levels are in dBFS (RMS of the tone); turning them into anything physical is
 * [AudiogramCalibration]'s job.
 */
public class ThresholdSearch(
    startDb: Double = DEFAULT_START_DB,
    private val minDb: Double = DEFAULT_MIN_DB,
    private val maxDb: Double = DEFAULT_MAX_DB,
) {
    /** The level to present next. */
    public var levelDb: Double = startDb.coerceIn(minDb, maxDb)
        private set

    /** The answer, once [isDone]; null while searching or if it could not be found. */
    public var thresholdDb: Double? = null
        private set

    public var isDone: Boolean = false
        private set

    /** True when the search ended at the top of the range without the tone being heard reliably. */
    public var notHeardAtMax: Boolean = false
        private set

    public var presentations: Int = 0
        private set

    private var familiarised = false
    private var previousHeard: Boolean? = null
    private val ascendingHeard = HashMap<Double, Int>()
    private var missesAtMax = 0

    /** Records the response to the tone at [levelDb] and moves to the next level. */
    public fun record(heard: Boolean) {
        check(!isDone) { "the search is finished" }
        presentations++
        val level = levelDb

        if (!familiarised) {
            // Familiarisation: climb in 10 dB steps until the tone is first heard, then start
            // the bracketing from there.
            if (heard) {
                familiarised = true
                previousHeard = true
                levelDb = (level - STEP_DOWN_DB).coerceAtLeast(minDb)
            } else if (level >= maxDb) {
                finishUnheard()
            } else {
                levelDb = (level + FAMILIARISE_STEP_DB).coerceAtMost(maxDb)
            }
            return
        }

        val ascending = previousHeard == false
        if (heard && ascending) {
            val count = (ascendingHeard[level] ?: 0) + 1
            ascendingHeard[level] = count
            if (count >= REQUIRED_ASCENDING) {
                finish(level)
                return
            }
        }

        if (!heard && level >= maxDb) {
            missesAtMax++
            if (missesAtMax >= REQUIRED_ASCENDING) {
                finishUnheard()
                return
            }
        }

        previousHeard = heard
        levelDb = if (heard) {
            (level - STEP_DOWN_DB).coerceAtLeast(minDb)
        } else {
            (level + STEP_UP_DB).coerceAtMost(maxDb)
        }

        if (presentations >= MAX_PRESENTATIONS) {
            // Responses that never settle: report the lowest level heard twice on the way up, or
            // failing that, once - flagged by the caller as unreliable through [presentations].
            val fallback = ascendingHeard.filterValues { it >= 1 }.keys.minOrNull()
            if (fallback != null) finish(fallback) else finishUnheard()
        }
    }

    private fun finish(level: Double) {
        thresholdDb = level
        isDone = true
    }

    private fun finishUnheard() {
        thresholdDb = null
        notHeardAtMax = true
        isDone = true
    }

    public companion object {
        /** Well above most thresholds at a sensible volume, well below discomfort. */
        public const val DEFAULT_START_DB: Double = -50.0
        public const val DEFAULT_MIN_DB: Double = -110.0
        public const val DEFAULT_MAX_DB: Double = -10.0

        public const val STEP_DOWN_DB: Double = 10.0
        public const val STEP_UP_DB: Double = 5.0
        public const val FAMILIARISE_STEP_DB: Double = 10.0
        public const val REQUIRED_ASCENDING: Int = 2
        public const val MAX_PRESENTATIONS: Int = 30
    }
}
