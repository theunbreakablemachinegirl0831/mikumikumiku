package mmm.training.audiometry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountingTrialTest {

    @Test
    fun `every count from one to five comes up, and each trial fits its window with countable gaps`() {
        val random = Random(42)
        val seen = HashSet<Int>()
        repeat(2000) {
            val trial = CountingProtocol.trial(random)
            seen += trial.count
            assertEquals(trial.count, trial.onsetsMs.size)
            assertTrue(trial.onsetsMs.first() >= CountingProtocol.EDGE_MS)
            assertTrue(trial.onsetsMs.last() + CountingProtocol.PULSE_MS <= CountingProtocol.WINDOW_MS - CountingProtocol.EDGE_MS + 1)
            trial.onsetsMs.zipWithNext { a, b ->
                assertTrue(b - a >= CountingProtocol.PULSE_MS + CountingProtocol.MIN_GAP_MS - 1, "gap ${b - a} in $trial")
            }
        }
        assertEquals((1..5).toSet(), seen)
    }

    @Test
    fun `timing varies, so the pulses cannot be counted on rhythm`() {
        val random = Random(7)
        val firstOnsets = (1..200).map { CountingProtocol.trial(random).onsetsMs.first() }.toSet()
        assertTrue(firstOnsets.size > 100)
    }

    @Test
    fun `only an exact count is heard, and an overcount is a false alarm`() {
        val trial = CountingTrial(3, listOf(300, 1200, 2500))
        assertTrue(CountingProtocol.judge(trial, 3).heard)
        assertFalse(CountingProtocol.judge(trial, 2).heard)
        assertFalse(CountingProtocol.judge(trial, 2).falseAlarm)
        assertFalse(CountingProtocol.judge(trial, 0).heard)
        assertTrue(CountingProtocol.judge(trial, 4).falseAlarm)
    }

    @Test
    fun `pulses are where the trial says`() {
        val trial = CountingTrial(2, listOf(500, 2000))
        assertTrue(trial.pulseAt(600.0))
        assertFalse(trial.pulseAt(800.0))
        assertTrue(trial.pulseAt(2249.0))
        assertFalse(trial.pulseAt(2250.0))
    }

    @Test
    fun `a listener who counts right above threshold and hears nothing below is found within a step`() {
        val random = Random(3)
        for (threshold in listOf(-80.0, -55.0, -30.0)) {
            val search = ThresholdSearch()
            while (!search.isDone) {
                val trial = CountingProtocol.trial(random)
                val answer = if (search.levelDb >= threshold) trial.count else 0
                search.record(CountingProtocol.judge(trial, answer).heard)
            }
            val found = search.thresholdDb!!
            assertTrue(found >= threshold && found < threshold + ThresholdSearch.STEP_UP_DB, "$threshold -> $found")
        }
    }
}
