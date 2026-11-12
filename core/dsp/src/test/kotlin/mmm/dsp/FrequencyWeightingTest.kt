package mmm.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrequencyWeightingTest {

    @Test
    fun `A-weighting matches the IEC 61672 table`() {
        // Nominal values from the standard's table, to its 0.1 dB.
        val table = mapOf(
            20.0 to -50.5, 50.0 to -30.2, 100.0 to -19.1, 200.0 to -10.9, 500.0 to -3.2,
            1000.0 to 0.0, 2000.0 to 1.2, 4000.0 to 1.0, 8000.0 to -1.1, 10000.0 to -2.5, 20000.0 to -9.3,
        )
        for ((f, db) in table) assertEquals(db, FrequencyWeighting.aWeightingDb(f), 0.15, "$f Hz")
    }

    @Test
    fun `equal-loudness contours pass through their phon value at 1 kHz`() {
        for (phon in listOf(20.0, 40.0, 60.0, 80.0)) {
            assertEquals(phon, FrequencyWeighting.equalLoudnessSpl(1000.0, phon), 0.05, "$phon phon")
            assertEquals(0.0, FrequencyWeighting.equalLoudnessWeightDb(1000.0, phon), 1e-9)
        }
    }

    @Test
    fun `equal-loudness values match ISO 226 2003 at its frequencies`() {
        // Published 40-phon contour values (ISO 226:2003), to 0.1 dB.
        val forty = mapOf(20.0 to 99.9, 100.0 to 64.4, 500.0 to 43.1, 4000.0 to 36.6, 12500.0 to 51.5)
        for ((f, spl) in forty) assertEquals(spl, FrequencyWeighting.equalLoudnessSpl(f, 40.0), 0.1, "$f Hz")
    }

    @Test
    fun `contours flatten as they get louder, and the ear is most sensitive near 3 to 4 kHz`() {
        val bass40 = FrequencyWeighting.equalLoudnessWeightDb(50.0, 40.0)
        val bass80 = FrequencyWeighting.equalLoudnessWeightDb(50.0, 80.0)
        assertTrue(bass40 < bass80, "50 Hz weighs less at 40 phon ($bass40) than at 80 ($bass80)")
        assertTrue(FrequencyWeighting.equalLoudnessWeightDb(3500.0, 60.0) > 0)
    }

    @Test
    fun `between table points the contour is smooth and does not overshoot`() {
        val f = FrequencyWeighting.ISO_F
        val table = FrequencyWeighting.contourAtTable(60.0)
        for (i in 0 until f.size - 1) {
            val mid = kotlin.math.sqrt(f[i] * f[i + 1])
            val v = FrequencyWeighting.equalLoudnessSpl(mid, 60.0)
            assertTrue(v >= minOf(table[i], table[i + 1]) - 1e-9 && v <= maxOf(table[i], table[i + 1]) + 1e-9, "between ${f[i]} and ${f[i + 1]}: $v")
        }
    }
}
