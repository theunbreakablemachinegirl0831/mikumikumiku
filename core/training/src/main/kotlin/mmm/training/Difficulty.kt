package mmm.training

/** One rung of a family's difficulty ladder. */
public data class LevelSpec(
    val index: Int,
    val name: String,
    val description: String,
)

/**
 * Level-unlock rule, in the spirit of "How to Listen": you advance when you have demonstrated the
 * current level over a window of trials, and you drop back if the level is clearly beyond you.
 *
 * @param window how many recent trials are considered
 * @param advanceAccuracy mean credit needed to move up
 * @param retreatAccuracy mean credit below which you move down
 */
public class LevelProgression(
    public val maxLevel: Int,
    private val window: Int = 10,
    private val advanceAccuracy: Double = 0.8,
    private val retreatAccuracy: Double = 0.4,
    startLevel: Int = 0,
) {
    private val recent = ArrayDeque<Double>()

    public var level: Int = startLevel.coerceIn(0, maxLevel)
        private set

    /** Highest level ever reached, so a bad run does not erase the learner's progress display. */
    public var bestLevel: Int = level
        private set

    public val trialsAtLevel: Int get() = recent.size

    public val recentAccuracy: Double
        get() = if (recent.isEmpty()) 0.0 else recent.average()

    /** @return the level after applying [credit], which may be unchanged. */
    public fun record(credit: Double): Int {
        recent.addLast(credit.coerceIn(0.0, 1.0))
        while (recent.size > window) recent.removeFirst()

        if (recent.size < window) return level

        val accuracy = recent.average()
        when {
            accuracy >= advanceAccuracy && level < maxLevel -> {
                level++
                bestLevel = maxOf(bestLevel, level)
                recent.clear()
            }
            accuracy < retreatAccuracy && level > 0 -> {
                level--
                recent.clear()
            }
        }
        return level
    }

    public fun reset(toLevel: Int = 0) {
        level = toLevel.coerceIn(0, maxLevel)
        recent.clear()
    }
}

/**
 * Transformed up-down staircase for threshold measurement (Levitt 1971).
 *
 * The level ladder answers "what can you do"; this answers "how small a change can you still
 * hear", by converging on the artifact magnitude you get right about 70.7 % of the time. Used by
 * the threshold-test mode rather than by ordinary practice.
 *
 * @param stepDown consecutive correct answers needed before the artifact is made subtler
 */
public class Staircase(
    initialValue: Double,
    private val minValue: Double,
    private val maxValue: Double,
    private var stepSize: Double,
    private val minStepSize: Double,
    private val stepDown: Int = 2,
    private val reversalsToConverge: Int = 8,
) {
    private var consecutiveCorrect = 0
    private var lastDirection = 0
    private val reversalValues = mutableListOf<Double>()

    /** Current artifact magnitude - smaller means harder. */
    public var value: Double = initialValue.coerceIn(minValue, maxValue)
        private set

    public val reversals: Int get() = reversalValues.size

    public val converged: Boolean get() = reversalValues.size >= reversalsToConverge

    /**
     * Threshold estimate: the mean of the last reversals, discarding the first two where the
     * staircase is still travelling rather than hunting.
     */
    public val threshold: Double?
        get() = if (reversalValues.size < 4) null
        else reversalValues.drop(2).average()

    public fun record(correct: Boolean) {
        val direction: Int
        if (correct) {
            consecutiveCorrect++
            if (consecutiveCorrect < stepDown) return
            consecutiveCorrect = 0
            direction = -1
        } else {
            consecutiveCorrect = 0
            direction = +1
        }

        if (lastDirection != 0 && direction != lastDirection) {
            reversalValues += value
            // Halve the step at each reversal, down to the floor, to home in.
            stepSize = (stepSize / 2).coerceAtLeast(minStepSize)
        }
        lastDirection = direction
        value = (value + direction * stepSize).coerceIn(minValue, maxValue)
    }
}
