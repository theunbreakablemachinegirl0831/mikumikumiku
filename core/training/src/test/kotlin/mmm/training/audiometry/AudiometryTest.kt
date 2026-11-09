package mmm.training.audiometry

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThresholdSearchTest {

    /** Runs a search against a listener who hears everything at or above [threshold]. */
    private fun run(threshold: Double, start: Double = ThresholdSearch.DEFAULT_START_DB): ThresholdSearch {
        val search = ThresholdSearch(startDb = start)
        while (!search.isDone) search.record(search.levelDb >= threshold)
        return search
    }

    @Test
    fun `an ideal listener's threshold is found within one step`() {
        for (threshold in listOf(-95.0, -80.0, -63.0, -47.0, -30.0)) {
            val found = run(threshold).thresholdDb!!
            assertTrue(found >= threshold && found < threshold + ThresholdSearch.STEP_UP_DB, "true $threshold, found $found")
        }
    }

    @Test
    fun `a tone first missed is climbed to in 10 dB steps`() {
        val search = ThresholdSearch(startDb = -50.0)
        search.record(false)
        assertEquals(-40.0, search.levelDb)
        search.record(true)
        assertEquals(-50.0, search.levelDb, "after the first tone heard the bracketing starts 10 dB down")
    }

    @Test
    fun `a tone never heard ends at the top of the range`() {
        val search = run(threshold = 100.0)
        assertTrue(search.isDone)
        assertNull(search.thresholdDb)
        assertTrue(search.notHeardAtMax)
    }

    @Test
    fun `a noisy listener still lands close and the search always ends`() {
        val random = Random(4)
        var worst = 0.0
        repeat(200) {
            val truth = -90.0 + random.nextDouble() * 50
            val search = ThresholdSearch()
            while (!search.isDone) {
                // A few dB wide psychometric slope around the true threshold.
                val p = 1.0 / (1.0 + kotlin.math.exp(-(search.levelDb - truth) / 1.5))
                search.record(random.nextDouble() < p)
            }
            assertTrue(search.presentations <= ThresholdSearch.MAX_PRESENTATIONS)
            search.thresholdDb?.let { worst = maxOf(worst, abs(it - truth)) }
        }
        assertTrue(worst <= 12.0, "worst error $worst dB")
    }
}

class AudiogramCalibrationTest {

    private fun result(vararg points: ThresholdPoint) = AudiogramResult(
        points = points.toList(),
        falseAlarms = 0,
        catchTrials = 4,
        retestDifferenceDb = emptyMap(),
        earphoneName = null,
        timestampMillis = 0,
    )

    @Test
    fun `normal hearing on a flat earphone comes out flat`() {
        // Thresholds that follow the reference curve exactly are normal everywhere.
        val points = AudiogramCalibration.REFERENCE_RELATIVE_DB.flatMap { (f, ref) ->
            Ear.entries.map { ThresholdPoint(it, f, -80.0 + ref, 6) }
        }
        val levels = AudiogramCalibration.relativeHearingLevels(result(*points.toTypedArray())) { 0.0 }
        assertTrue(levels.values.all { abs(it) < 1e-9 }, "$levels")
    }

    @Test
    fun `an earphone loud at 3 kHz makes the raw threshold there look better than it is`() {
        val points = arrayOf(
            ThresholdPoint(Ear.LEFT, 1000.0, -80.0, 6),
            ThresholdPoint(Ear.LEFT, 3000.0, -80.0 + 3.5 - 6.0, 6),
        )
        val levels = AudiogramCalibration.relativeHearingLevels(result(*points)) { f -> if (f == 3000.0) 6.0 else 0.0 }
        assertEquals(0.0, levels[Ear.LEFT to 3000.0]!!, 1e-9)
    }

    @Test
    fun `a worse ear stays worse`() {
        val points = arrayOf(
            ThresholdPoint(Ear.LEFT, 1000.0, -80.0, 6),
            ThresholdPoint(Ear.RIGHT, 1000.0, -70.0, 6),
        )
        val levels = AudiogramCalibration.relativeHearingLevels(result(*points)) { 0.0 }
        assertEquals(10.0, levels[Ear.RIGHT to 1000.0]!! - levels[Ear.LEFT to 1000.0]!!, 1e-9)
    }

    @Test
    fun `a result with a bad retest is not reliable`() {
        val shaky = result().copy(retestDifferenceDb = mapOf(Ear.LEFT to 15.0))
        assertTrue(!shaky.reliable)
    }
}
