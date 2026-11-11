package mmm.eq

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqStackTest {

    private val boost = EqFilter(EqFilterType.PEAK, 1000.0, 1.0, 6.0)
    private val cut = EqFilter(EqFilterType.PEAK, 4000.0, 2.0, -5.0)

    @Test
    fun `nothing to play gives no profile`() {
        assertNull(EqStack.combine(emptyList()))
        assertNull(EqStack.combine(listOf(emptyList(), listOf(boost.copy(gainDb = 0.0)))))
    }

    @Test
    fun `headroom follows where the layers add up`() {
        // Two boosts at the same place stack to 12 dB, and the preamp has to cover both.
        val stacked = assertNotNull(EqStack.combine(listOf(listOf(boost), listOf(boost))))
        assertEquals(-(12.0 + EqStack.HEADROOM_DB), stacked.preampDb, 0.2)
        assertTrue(stacked.response().decibels.max() <= 0.0)
    }

    @Test
    fun `cuts alone need no headroom, and a manual preamp is kept`() {
        val profile = assertNotNull(EqStack.combine(listOf(listOf(cut)), manualPreampDb = -2.0))
        assertEquals(-2.0, profile.preampDb, 1e-9)
        val unprotected = assertNotNull(EqStack.combine(listOf(listOf(boost)), autoHeadroom = false))
        assertEquals(0.0, unprotected.preampDb, 1e-9)
    }

    @Test
    fun `a manual preamp alone is still a profile`() {
        val profile = assertNotNull(EqStack.combine(emptyList(), manualPreampDb = -3.0))
        assertTrue(profile.filters.isEmpty())
    }

    @Test
    fun `updating a playing processor in place gives the new response without a restart`() {
        val rate = 48000
        val frames = rate
        val processor = EqProfile(listOf(boost), 0.0).createProcessor()
        processor.prepare(AudioFormat(rate, 1, frames))
        val buffer = AudioBuffer(1, frames)
        fun fill() {
            buffer.frames = frames
            for (i in 0 until frames) buffer.data[0][i] = (0.1 * sin(2 * PI * 1000.0 * i / rate)).toFloat()
        }
        fill()
        processor.process(buffer)
        processor.update(EqProfile(listOf(boost.copy(gainDb = -6.0)), 0.0))
        fill()
        processor.process(buffer)
        val peak = (frames / 2 until frames).maxOf { abs(buffer.data[0][it]) }
        assertEquals(0.1 * 0.501, peak.toDouble(), 0.003)
    }
}

class GraphicEqTest {

    @Test
    fun `flat sliders make no filters`() {
        assertTrue(GraphicEq.OCTAVE.filters(List(10) { 0.0 }).isEmpty())
    }

    @Test
    fun `the response passes through what the sliders show`() {
        for (eq in listOf(GraphicEq.OCTAVE, GraphicEq.THIRD_OCTAVE)) {
            // Every slider up by the same amount is the hard case: plain constant-Q bands would overshoot.
            val level = EqProfile(eq.filters(List(eq.size) { 6.0 }), 0.0)
            for (centre in eq.centres) assertEquals(6.0, level.magnitudeDbAt(centre), 0.3, "${eq.size} bands at $centre Hz")

            val sliders = List(eq.size) { if (it % 3 == 0) 5.0 else if (it % 3 == 1) -4.0 else 0.0 }
            val zigzag = EqProfile(eq.filters(sliders), 0.0)
            eq.centres.forEachIndexed { i, centre ->
                assertEquals(sliders[i], zigzag.magnitudeDbAt(centre), 0.5, "${eq.size} bands at $centre Hz")
            }
        }
    }

    @Test
    fun `one slider moves its own band and leaves distant ones alone`() {
        val sliders = List(10) { if (it == 5) 8.0 else 0.0 }
        val profile = EqProfile(GraphicEq.OCTAVE.filters(sliders), 0.0)
        assertEquals(8.0, profile.magnitudeDbAt(1000.0), 0.3)
        assertTrue(abs(profile.magnitudeDbAt(62.5)) < 0.5)
        assertTrue(abs(profile.magnitudeDbAt(16000.0)) < 0.5)
    }

    @Test
    fun `layout is chosen by slider count`() {
        assertEquals(31, GraphicEq.of(31).size)
        assertEquals(10, GraphicEq.of(10).size)
    }
}

class MatchRuleTest {

    private val grid = FrequencyResponse.logGrid()
    private val flat = FrequencyResponse(grid, grid.map { 90.0 })

    /** Flat, except [db] more between [fromHz] and [toHz]. */
    private fun withStep(fromHz: Double, toHz: Double, db: Double) =
        FrequencyResponse(grid, grid.map { if (it in fromHz..toHz) 90.0 + db else 90.0 })

    @Test
    fun `a rule over the whole spectrum is the ordinary fit`() {
        val target = FrequencyResponse(grid, grid.map { 90.0 + 4.0 * kotlin.math.exp(-log2(it / 3000.0).let { o -> o * o }) })
        val fitter = PeqFitter()
        val whole = fitter.fitRules(flat, listOf(MatchRule(target)))
        assertEquals(fitter.fit(flat, target).profile, whole.profile)
    }

    @Test
    fun `a rule only changes its own range`() {
        // The other earphone is brighter around 3 kHz and heavier in the bass; only the treble is asked for.
        val other = FrequencyResponse(grid, grid.map { f ->
            90.0 + 6.0 * kotlin.math.exp(-log2(f / 3000.0).let { it * it } * 2) + if (f < 120.0) 6.0 else 0.0
        })
        val result = PeqFitter().fitRules(flat, listOf(MatchRule(other, 1500.0, 6000.0)))
        val response = result.profile.copy(preampDb = 0.0)
        assertEquals(6.0, response.magnitudeDbAt(3000.0), 1.5)
        assertTrue(abs(response.magnitudeDbAt(60.0)) < 1.0, "bass was not asked for: ${response.magnitudeDbAt(60.0)}")
    }

    @Test
    fun `two rules take different earphones in different ranges`() {
        val vocal = withStep(700.0, 4000.0, 4.0)
        val bass = withStep(20.0, 150.0, 6.0)
        val result = PeqFitter().fitRules(
            flat,
            listOf(MatchRule(vocal, 500.0, 8000.0), MatchRule(bass, 20.0, 300.0)),
        )
        val response = result.profile.copy(preampDb = 0.0)
        // Each difference is centred on its own midrange, so compare shapes inside each range.
        assertTrue(response.magnitudeDbAt(2000.0) - response.magnitudeDbAt(12000.0) > 1.5, "vocal lift missing")
        assertTrue(response.magnitudeDbAt(50.0) - response.magnitudeDbAt(250.0) > 3.0, "bass lift missing")
        assertTrue(result.rmsAfterDb < result.rmsBeforeDb)
    }

    @Test
    fun `a rule fades out past its edges`() {
        val rule = MatchRule(flat, 500.0, 2000.0)
        assertEquals(1.0, rule.weightAt(1000.0))
        assertEquals(1.0, rule.weightAt(500.0))
        assertEquals(0.0, rule.weightAt(100.0))
        assertTrue(rule.weightAt(450.0) in 0.3..0.99)
    }
}
