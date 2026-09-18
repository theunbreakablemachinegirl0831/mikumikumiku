package mmm.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BiquadTest {

    private val sampleRate = 48000

    @Test
    fun `peaking filter hits its target gain at the centre frequency`() {
        for (gain in listOf(-12.0, -6.0, 3.0, 6.0, 12.0)) {
            val c = BiquadDesign.peaking(1000.0, 4.0, gain, sampleRate)
            val measured = c.magnitudeDbAt(1000.0, sampleRate)
            assertTrue(
                abs(measured - gain) < 0.05,
                "peaking $gain dB measured ${"%.3f".format(measured)} dB at centre",
            )
        }
    }

    @Test
    fun `peaking filter is transparent far from the centre frequency`() {
        val c = BiquadDesign.peaking(1000.0, 8.0, 12.0, sampleRate)
        assertTrue(abs(c.magnitudeDbAt(50.0, sampleRate)) < 0.5)
        assertTrue(abs(c.magnitudeDbAt(16000.0, sampleRate)) < 0.5)
    }

    @Test
    fun `low pass is minus 3 dB at cutoff and rolls off above it`() {
        val c = BiquadDesign.lowPass(1000.0, 0.7071, sampleRate)
        assertTrue(abs(c.magnitudeDbAt(1000.0, sampleRate) - (-3.0)) < 0.2)
        val oneOctaveUp = c.magnitudeDbAt(2000.0, sampleRate)
        val twoOctavesUp = c.magnitudeDbAt(4000.0, sampleRate)
        // A second-order section is 12 dB/oct in the stopband.
        assertTrue(oneOctaveUp - twoOctavesUp in 10.0..14.0, "slope was ${oneOctaveUp - twoOctavesUp} dB/oct")
    }

    @Test
    fun `q for bandwidth round trips to the expected minus 3 dB width`() {
        val q = BiquadDesign.qForBandwidthOctaves(1.0)
        val c = BiquadDesign.peaking(1000.0, q, 12.0, sampleRate)
        // At the octave edges a peaking filter is at half its dB gain.
        val lower = c.magnitudeDbAt(1000.0 / Math.sqrt(2.0), sampleRate)
        val upper = c.magnitudeDbAt(1000.0 * Math.sqrt(2.0), sampleRate)
        assertTrue(abs(lower - 6.0) < 0.6, "lower edge was $lower dB")
        assertTrue(abs(upper - 6.0) < 0.6, "upper edge was $upper dB")
    }

    @Test
    fun `filter state carries across blocks`() {
        val format = AudioFormat(sampleRate, 1, 256)
        val filter = BiquadFilter(1).apply {
            prepare(format)
            coefficients = BiquadDesign.lowPass(500.0, 0.7071, sampleRate)
        }
        val signal = SignalGenerator.sine(2000.0, 1024, sampleRate, channels = 1)

        // Whole-buffer run.
        val whole = signal.copy()
        filter.reset()
        filter.process(whole)

        // Same signal in 256-frame blocks.
        val blocked = signal.copy()
        filter.reset()
        val block = AudioBuffer(1, 256)
        for (start in 0 until 1024 step 256) {
            block.frames = 256
            signal.data[0].copyInto(block.data[0], 0, start, start + 256)
            filter.process(block)
            block.data[0].copyInto(blocked.data[0], start, 0, 256)
        }

        for (i in 0 until 1024) {
            assertEquals(whole.data[0][i], blocked.data[0][i], 1e-5f, "sample $i diverged")
        }
    }
}
