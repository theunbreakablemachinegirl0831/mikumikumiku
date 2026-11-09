package mmm.eq

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrequencyResponseTest {

    @Test
    fun `parses squig and REW text with its preamble`() {
        val text = """
            Smoothed Frequency Response
            Resolution = 1/24 octave
            Numpoints = 3
                20.000	 100.5
              1000.000	  94.0
             20000.000	  60.25
        """.trimIndent()
        val response = FrequencyResponse.parse(text)
        assertEquals(3, response.size)
        assertEquals(94.0, response.at(1000.0), 1e-9)
    }

    @Test
    fun `parses CSV with a header row`() {
        val response = FrequencyResponse.parse("frequency,raw\n100,1.5\n200,2.5\n")
        assertEquals(listOf(100.0, 200.0), response.frequencies)
    }

    @Test
    fun `text without numbers is refused`() {
        assertFailsWith<IllegalArgumentException> { FrequencyResponse.parse("hello\nworld") }
    }

    @Test
    fun `interpolates in log frequency and holds past the ends`() {
        val response = FrequencyResponse(listOf(100.0, 400.0), listOf(0.0, 12.0))
        assertEquals(6.0, response.at(200.0), 1e-9, "200 Hz is halfway between in octaves")
        assertEquals(0.0, response.at(10.0))
        assertEquals(12.0, response.at(20000.0))
    }

    @Test
    fun `smoothing keeps a flat line flat and a slope a slope`() {
        val grid = FrequencyResponse.logGrid()
        val flat = FrequencyResponse(grid, grid.map { 3.0 }).smooth(3)
        assertTrue(flat.decibels.all { abs(it - 3.0) < 1e-9 })

        val slope = FrequencyResponse(grid, grid.map { 2.0 * log2(it / 1000.0) }).smooth(6)
        assertEquals(0.0, slope.at(1000.0), 0.05)
    }

    @Test
    fun `smoothing flattens a ripple narrower than its window`() {
        val grid = FrequencyResponse.logGrid()
        val ripple = FrequencyResponse(grid, grid.indices.map { if (it % 2 == 0) 3.0 else -3.0 }).smooth(6)
        assertTrue(ripple.decibels.drop(10).dropLast(10).all { abs(it) < 0.5 })
    }

    @Test
    fun `normalising puts the midrange at zero`() {
        val grid = FrequencyResponse.logGrid()
        val response = FrequencyResponse(grid, grid.map { 87.0 }).normalized()
        assertEquals(0.0, response.at(700.0), 1e-9)
    }
}

class EqProfileTest {

    private val profile = EqProfile(
        filters = listOf(
            EqFilter(EqFilterType.LOW_SHELF, 105.0, 0.7, 2.5),
            EqFilter(EqFilterType.PEAK, 3100.0, 1.2, 4.2),
            EqFilter(EqFilterType.HIGH_SHELF, 10000.0, 0.7, -3.0),
        ),
        preampDb = -5.1,
    )

    @Test
    fun `Equalizer APO text round trips`() {
        val text = profile.toApoText()
        assertTrue(text.startsWith("Preamp: -5.1 dB"))
        assertTrue("Filter 2: ON PK Fc 3100 Hz Gain 4.2 dB Q 1.20" in text, text)
        assertEquals(profile, EqProfile.parseApo(text))
    }

    @Test
    fun `reads an AutoEq file`() {
        val autoEq = """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 3.8 dB Q 0.70
            Filter 2: ON PK Fc 2954 Hz Gain 5.5 dB Q 1.66
            Filter 10: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
        """.trimIndent()
        val parsed = EqProfile.parseApo(autoEq)
        assertEquals(-6.4, parsed.preampDb)
        assertEquals(3, parsed.filters.size)
        assertEquals(EqFilterType.HIGH_SHELF, parsed.filters.last().type)
    }

    @Test
    fun `the processor applies the response the profile predicts`() {
        // A sine through the processor comes out at the level magnitudeDbAt promises.
        val rate = 48000
        val frequency = 3100.0
        val frames = rate / 2
        val buffer = AudioBuffer(1, frames).apply {
            this.frames = frames
            for (i in 0 until frames) data[0][i] = (0.1 * sin(2 * PI * frequency * i / rate)).toFloat()
        }
        val processor = profile.createProcessor()
        processor.prepare(AudioFormat(rate, 1, frames))
        processor.process(buffer)

        var sum = 0.0
        val settled = frames / 2
        for (i in settled until frames) sum += buffer.data[0][i].toDouble().let { it * it }
        val outDb = 20 * log10(sqrt(sum / (frames - settled)) / (0.1 / sqrt(2.0)))
        assertEquals(profile.magnitudeDbAt(frequency, rate), outDb, 0.1)
    }
}

class PeqFitterTest {

    private val grid = FrequencyResponse.logGrid()

    /** A measurement that is exactly the target minus [eq]: a perfect fit is possible. */
    private fun measuredFor(target: FrequencyResponse, eq: EqProfile): FrequencyResponse =
        FrequencyResponse(grid, grid.map { target.at(it) - eq.magnitudeDbAt(it) + 90.0 })

    private val target = FrequencyResponse(grid, grid.map { f ->
        // Roughly in-ear shaped: a bass shelf, ear gain near 3 kHz, treble roll-off.
        val bass = 6.0 / (1 + (f / 120.0) * (f / 120.0))
        val ear = 9.0 * kotlin.math.exp(-(log2(f / 3000.0) * log2(f / 3000.0)) / 0.8)
        val roll = if (f > 8000) -6.0 * log2(f / 8000.0) else 0.0
        60.0 + bass + ear + roll
    })

    @Test
    fun `recovers a response made from a known EQ`() {
        val hidden = EqProfile(
            listOf(
                EqFilter(EqFilterType.LOW_SHELF, 90.0, 0.7, -3.0),
                EqFilter(EqFilterType.PEAK, 1300.0, 1.5, 3.0),
                EqFilter(EqFilterType.PEAK, 4000.0, 2.0, -5.0),
                EqFilter(EqFilterType.PEAK, 6500.0, 3.0, 4.0),
            ),
            0.0,
        )
        val result = PeqFitter().fit(measuredFor(target, hidden), target)
        assertTrue(result.rmsBeforeDb > 2.0, "the test curve should start well off target")
        assertTrue(result.rmsAfterDb < 0.5, "left ${result.rmsAfterDb} dB RMS")
    }

    @Test
    fun `respects the limits it is given`() {
        val settings = PeqFitSettings(maxFilters = 6, maxBoostDb = 4.0, maxCutDb = 8.0, maxQ = 3.0)
        val awkward = FrequencyResponse(grid, grid.map { f -> 90.0 + 15.0 * sin(log2(f / 20.0) * 2.1) })
        val result = PeqFitter(settings).fit(awkward, target)
        val filters = result.profile.filters
        assertTrue(filters.size <= 6)
        for (filter in filters) {
            assertTrue(filter.gainDb <= 4.0 + 1e-9 && filter.gainDb >= -8.0 - 1e-9, "$filter")
            if (filter.type == EqFilterType.PEAK) assertTrue(filter.q in 0.5..3.0, "$filter")
        }
    }

    @Test
    fun `peaks stay below the detail limit and only a shelf works above it`() {
        val wild = FrequencyResponse(grid, grid.map { f ->
            target.at(f) + 90.0 + if (f > 10000) 8.0 * sin(log2(f) * 40) else 0.0
        })
        val limited = PeqFitter().fit(wild, target).profile
        assertTrue(limited.filters.filter { it.type == EqFilterType.PEAK }.all { it.frequency <= 10000.0 })

        val full = PeqFitter(PeqFitSettings.FULL_RANGE).fit(wild, target).profile
        assertTrue(full.filters.none { it.type == EqFilterType.HIGH_SHELF })
    }

    @Test
    fun `the preamp leaves the boosted EQ just under 0 dB`() {
        val hidden = EqProfile(listOf(EqFilter(EqFilterType.PEAK, 3000.0, 1.0, -5.0)), 0.0)
        val profile = PeqFitter().fit(measuredFor(target, hidden), target).profile
        val loudest = grid.maxOf { profile.magnitudeDbAt(it) }
        assertTrue(loudest <= 0.0 && loudest > -1.0, "loudest point $loudest dB")
    }

    @Test
    fun `a measurement already on target needs nothing`() {
        val result = PeqFitter().fit(target.shifted(30.0), target)
        assertTrue(result.profile.filters.isEmpty(), "${result.profile.filters}")
        assertEquals(0.0, result.profile.preampDb)
    }
}

class EarphoneCalibrationTest {

    private val grid = FrequencyResponse.logGrid()

    @Test
    fun `a level is moved by how loud the earphone is there relative to 1 kHz`() {
        // An earphone 6 dB louder at 3 kHz than at 1 kHz, on a smooth slope.
        val measured = FrequencyResponse(grid, grid.map { 94.0 + 6.0 * log2(it / 1000.0) / log2(3.0) })
        val calibration = EarphoneCalibration(measured)
        assertEquals(0.0, calibration.relativeGainDb(1000.0), 0.1)
        assertEquals(6.0, calibration.relativeGainDb(3000.0), 0.2)
        assertEquals(-34.0, calibration.eardrumRelativeDb(-40.0, 3000.0), 0.2)
    }

    @Test
    fun `absolute level needs a sensitivity`() {
        val measured = FrequencyResponse(grid, grid.map { 94.0 })
        assertEquals(null, EarphoneCalibration(measured).eardrumSplDb(-20.0, 1000.0, 1.0))

        // 100 dB/V, 1 V full scale, -20 dBFS: 80 dB SPL.
        val calibrated = EarphoneCalibration(measured, EarphoneCalibration.Sensitivity(100.0))
        assertEquals(80.0, calibrated.eardrumSplDb(-20.0, 1000.0, 1.0)!!, 0.1)
    }

    @Test
    fun `dB per milliwatt converts through the impedance`() {
        // 32 ohms: 1 V is 31.25 mW, +14.95 dB over 1 mW.
        val s = EarphoneCalibration.Sensitivity.fromDbPerMilliwatt(110.0, 32.0)
        assertEquals(124.95, s.dbSplPerVolt, 0.01)
    }
}
