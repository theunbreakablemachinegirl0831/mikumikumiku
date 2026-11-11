package mmm.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HearingPrescriptionTest {

    /** A sloping high-frequency loss: normal in the bass, 60 dB at 8 kHz. */
    private val sloping = mapOf(
        250.0 to 10.0, 500.0 to 15.0, 1000.0 to 20.0, 2000.0 to 35.0,
        3000.0 to 45.0, 4000.0 to 50.0, 6000.0 to 55.0, 8000.0 to 60.0,
    )

    @Test
    fun `NAL-R follows its published formula`() {
        val gain = assertNotNull(HearingPrescription.insertionGain(HearingRule.NAL_R, sloping, speechShape = true))
        // X = 0.05 x (15 + 20 + 35) = 3.5
        assertEquals(3.5 + 0.31 * 10 - 17, gain.at(250.0), 1e-9)
        assertEquals(3.5 + 0.31 * 15 - 8, gain.at(500.0), 1e-9)
        assertEquals(3.5 + 0.31 * 20 + 1, gain.at(1000.0), 1e-9)
        assertEquals(3.5 + 0.31 * 50 - 2, gain.at(4000.0), 1e-9)
    }

    @Test
    fun `without the speech shaping only the listener's own loss is left`() {
        val gain = assertNotNull(HearingPrescription.insertionGain(HearingRule.NAL_R, sloping))
        assertEquals(3.5 + 0.31 * 10, gain.at(250.0), 1e-9)
        // Normal hearing then asks for nothing at all, instead of a bass cut meant for speech.
        val normal = HearingPrescription.FREQUENCIES.associateWith { 0.0 }
        val none = assertNotNull(HearingPrescription.insertionGain(HearingRule.NAL_R, normal))
        assertTrue(none.decibels.all { it == 0.0 })
    }

    @Test
    fun `the simple rules give their fraction of the loss`() {
        val half = assertNotNull(HearingPrescription.insertionGain(HearingRule.HALF_GAIN, sloping))
        assertEquals(25.0, half.at(4000.0), 1e-9)
        val third = assertNotNull(HearingPrescription.insertionGain(HearingRule.THIRD_GAIN, sloping))
        assertEquals(20.0, third.at(8000.0), 1e-9)
        val pogo = assertNotNull(HearingPrescription.insertionGain(HearingRule.POGO, sloping, speechShape = true))
        assertEquals(0.5 * 10 - 10, pogo.at(250.0), 1e-9)
        assertEquals(0.5 * 35, pogo.at(2000.0), 1e-9)
    }

    @Test
    fun `an unknown offset in the thresholds changes the level but not the shape`() {
        for (rule in HearingRule.entries) {
            val absolute = assertNotNull(HearingPrescription.insertionGain(rule, sloping))
            val relative = assertNotNull(HearingPrescription.insertionGain(rule, sloping.mapValues { it.value - 20.0 }))
            val offsets = HearingPrescription.FREQUENCIES.map { absolute.at(it) - relative.at(it) }
            assertTrue(offsets.max() - offsets.min() < 1e-9, "$rule: $offsets")
        }
    }

    @Test
    fun `strength scales the gain and missing data gives nothing`() {
        val full = assertNotNull(HearingPrescription.insertionGain(HearingRule.HALF_GAIN, sloping))
        val half = assertNotNull(HearingPrescription.insertionGain(HearingRule.HALF_GAIN, sloping, strength = 0.5))
        assertEquals(full.at(3000.0) / 2, half.at(3000.0), 1e-9)
        assertNull(HearingPrescription.insertionGain(HearingRule.NAL_R, mapOf(1000.0 to 10.0)))
    }

    @Test
    fun `the gain can be turned into a handful of filters`() {
        val gain = assertNotNull(HearingPrescription.insertionGain(HearingRule.NAL_R, sloping))
        val flat = FrequencyResponse(FrequencyResponse.logGrid(), FrequencyResponse.logGrid().map { 0.0 })
        val fit = PeqFitter(PeqFitSettings(maxFilters = 6, maxBoostDb = 12.0, maxTotalBoostDb = 15.0)).fit(flat, gain)
        val response = fit.profile.copy(preampDb = 0.0)
        // The treble ends up well above the bass, as the loss does.
        assertTrue(response.magnitudeDbAt(6000.0) - response.magnitudeDbAt(250.0) > 8.0)
        assertTrue(fit.profile.filters.size <= 6)
    }
}
