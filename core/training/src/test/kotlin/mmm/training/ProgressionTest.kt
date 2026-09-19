package mmm.training

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionTest {

    @Test
    fun `a run of correct answers moves the level up once per window`() {
        val progression = LevelProgression(maxLevel = 5, window = 10)
        repeat(9) { progression.record(1.0) }
        assertEquals(0, progression.level, "should not advance before the window is full")
        progression.record(1.0)
        assertEquals(1, progression.level)
    }

    @Test
    fun `a bad run drops the level but keeps the best reached`() {
        val progression = LevelProgression(maxLevel = 5, window = 10)
        repeat(10) { progression.record(1.0) }
        assertEquals(1, progression.level)
        repeat(10) { progression.record(0.0) }
        assertEquals(0, progression.level)
        assertEquals(1, progression.bestLevel, "the display should remember what was achieved")
    }

    @Test
    fun `middling performance holds the level steady`() {
        val progression = LevelProgression(maxLevel = 5, window = 10)
        repeat(40) { progression.record(0.6) }
        assertEquals(0, progression.level)
    }

    @Test
    fun `the level never runs off either end of the ladder`() {
        val progression = LevelProgression(maxLevel = 2, window = 4)
        repeat(100) { progression.record(1.0) }
        assertEquals(2, progression.level)
        repeat(100) { progression.record(0.0) }
        assertEquals(0, progression.level)
    }

    @Test
    fun `the staircase converges on a simulated listener's threshold`() {
        // A synthetic listener who hears any artifact above 4 dB and guesses below it.
        val trueThreshold = 4.0
        val random = Random(1234)
        val staircase = Staircase(
            initialValue = 12.0,
            minValue = 0.5,
            maxValue = 16.0,
            stepSize = 3.0,
            minStepSize = 0.25,
            stepDown = 2,
            reversalsToConverge = 10,
        )

        var trials = 0
        while (!staircase.converged && trials < 500) {
            val audible = staircase.value >= trueThreshold
            // Below threshold they are guessing between three alternatives.
            val correct = if (audible) true else random.nextInt(3) == 0
            staircase.record(correct)
            trials++
        }

        assertTrue(staircase.converged, "did not converge in $trials trials")
        val estimate = staircase.threshold
        assertTrue(estimate != null && abs(estimate - trueThreshold) < 1.5,
            "estimated $estimate, expected about $trueThreshold")
    }
}
