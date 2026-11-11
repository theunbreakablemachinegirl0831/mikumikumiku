package mmm.dsp

import mmm.dsp.artifacts.TubeBand
import mmm.dsp.artifacts.TubeProcessor
import mmm.dsp.artifacts.TubeSettings
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TubeHarmonicsTest {

    private val fftSize = 8192
    private val rate = TestSupport.SAMPLE_RATE

    /** Levels in dBFS of the fundamental and its 2nd and 3rd harmonics after [settings]. */
    private fun harmonics(settings: TubeSettings, bin: Int, amplitude: Float): Triple<Double, Double, Double> {
        val frames = fftSize * 3
        val buffer = SignalGenerator.sine(bin.toDouble() * rate / fftSize, frames, rate, channels = 1, amplitude = amplitude)
        val processor = TubeProcessor(settings)
        processor.prepare(TestSupport.format(frames, 1))
        processor.process(buffer)
        val slice = FloatArray(fftSize)
        buffer.data[0].copyInto(slice, 0, fftSize * 2, fftSize * 3)
        val spectrum = Fft(fftSize).magnitude(slice)
        // A sine of amplitude 1 reads 0.5 in a bin of this unwindowed, bin-centred FFT.
        fun dbfs(b: Int) = if (b >= spectrum.size) -240.0 else 20 * log10((spectrum[b] * 2).coerceAtLeast(1e-12))
        return Triple(dbfs(bin), dbfs(bin * 2), dbfs(bin * 3))
    }

    private fun mid(second: Double = TubeBand.OFF_DB, third: Double = TubeBand.OFF_DB) =
        TubeSettings(listOf(TubeBand(20.0, 200.0), TubeBand(200.0, 2000.0, second, third), TubeBand(2000.0, 20000.0)))

    // Bin 128 of 8192 at 48 kHz is 750 Hz: inside the middle band, away from its edges.
    private val bin = 128

    @Test
    fun `nothing set means nothing added`() {
        assertFalse(TubeSettings().active)
        val (fundamental, second, third) = harmonics(TubeSettings(), bin, 0.5f)
        assertEquals(-6.02, fundamental, 0.05)
        assertTrue(second < -120 && third < -120)
    }

    @Test
    fun `a full-scale tone gets the harmonics at the levels set`() {
        val (fundamental, second, third) = harmonics(mid(second = -40.0, third = -50.0), bin, 1.0f)
        assertEquals(0.0, fundamental, 0.3)
        assertEquals(-40.0, second, 1.5)
        assertEquals(-50.0, third, 1.5)
    }

    @Test
    fun `each harmonic can be made alone`() {
        val onlySecond = harmonics(mid(second = -30.0), bin, 1.0f)
        assertEquals(-30.0, onlySecond.second, 1.5)
        assertTrue(onlySecond.third < -90, "third was ${onlySecond.third}")

        val onlyThird = harmonics(mid(third = -30.0), bin, 1.0f)
        assertEquals(-30.0, onlyThird.third, 1.5)
        assertTrue(onlyThird.second < -90, "second was ${onlyThird.second}")
        assertEquals(0.0, onlyThird.first, 0.3)
    }

    @Test
    fun `quieter music gets proportionally less, as from a valve`() {
        // 20 dB down: the second harmonic falls 40 dB, the third 60 dB.
        val (fundamental, second, third) = harmonics(mid(second = -20.0, third = -20.0), bin, 0.1f)
        assertEquals(-20.0, fundamental, 0.3)
        assertEquals(-60.0, second, 2.0)
        assertEquals(-80.0, third, 2.5)
    }

    @Test
    fun `a band only works on its own part of the spectrum`() {
        // 750 Hz is outside the bass band, so harmonics set there leave it nearly alone.
        val bassOnly = TubeSettings(listOf(TubeBand(20.0, 100.0, -20.0, -20.0)))
        val (_, second, _) = harmonics(bassOnly, bin, 1.0f)
        assertTrue(second < -85, "second was $second")
    }

    @Test
    fun `nothing folds back from above the guard frequency`() {
        // 12 kHz: its third harmonic would be 36 kHz, which at 48 kHz would alias down to 12 kHz
        // and its second to 24 kHz. The band's ceiling keeps the shaper from being fed at all.
        val top = TubeSettings(listOf(TubeBand(2000.0, 20000.0, 0.0, 0.0)))
        val highBin = 2048
        val (fundamental, _, _) = harmonics(top, highBin, 0.5f)
        assertEquals(-6.02, fundamental, 0.3)
    }
}
