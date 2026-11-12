package mmm.eq

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicEqBandsTest {

    /** The Bravo-to-Harman fit, including its narrowest filter. */
    private val profile = EqProfile(
        listOf(
            EqFilter(EqFilterType.LOW_SHELF, 153.0, 0.7, 2.0),
            EqFilter(EqFilterType.PEAK, 665.0, 1.69, 1.6),
            EqFilter(EqFilterType.PEAK, 1341.0, 1.42, -3.3),
            EqFilter(EqFilterType.PEAK, 3418.0, 0.93, 6.0),
            EqFilter(EqFilterType.PEAK, 3935.0, 5.68, 1.5),
            EqFilter(EqFilterType.PEAK, 6181.0, 3.01, 5.4),
            EqFilter(EqFilterType.PEAK, 9571.0, 1.38, -5.1),
            EqFilter(EqFilterType.HIGH_SHELF, 16000.0, 0.7, 2.4),
        ),
        preampDb = -7.4,
    )

    @Test
    fun `bands follow the PEQ curve to within half a decibel`() {
        val bands = GraphicEqBands.from(profile)
        var worst = 0.0
        for (f in FrequencyResponse.logGrid(25.0, 18000.0, 96)) {
            worst = maxOf(worst, abs(bands.realizedDbAt(f) - profile.magnitudeDbAt(f)))
        }
        assertTrue(worst < 0.6, "worst step $worst dB")
    }

    @Test
    fun `cutoffs rise, the last covers the top, and the preamp is kept apart`() {
        val bands = GraphicEqBands.from(profile, bandsPerOctave = 12)
        assertTrue(bands.bands.zipWithNext().all { (a, b) -> b.cutoffHz > a.cutoffHz })
        assertTrue(bands.bands.last().cutoffHz >= 20000.0)
        assertEquals(-7.4, bands.preampDb)
        assertEquals(profile.magnitudeDbAt(1000.0) - profile.preampDb, bands.bands.first { it.cutoffHz > 1000.0 }.gainDb, 0.5)
    }

    @Test
    fun `a flat profile makes flat bands`() {
        val bands = GraphicEqBands.from(EqProfile.FLAT)
        assertTrue(bands.bands.all { it.gainDb == 0.0 })
    }
}
