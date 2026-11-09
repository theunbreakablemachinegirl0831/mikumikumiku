package mmm.dsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpectrumProfileTest {

    private val rate = 48000

    private fun pink(seconds: Double, seed: Int = 1): AudioBuffer =
        SignalGenerator.pinkNoise((rate * seconds).toInt(), channels = 2, random = Random(seed))

    /** Pink noise through a steep low-pass: nothing left above [cutoff]. */
    private fun dull(seconds: Double, cutoff: Double): AudioBuffer {
        val audio = pink(seconds, seed = 2)
        val cascade = BiquadCascade(8, 2)
        repeat(8) { cascade.setCoefficients(it, BiquadDesign.lowPass(cutoff, 0.707, rate)) }
        cascade.prepare(AudioFormat(rate, 2, audio.frames))
        cascade.process(audio)
        return audio
    }

    @Test
    fun `pink noise is usable everywhere`() {
        val profile = SpectrumProfile.analyze(pink(4.0), rate)
        val grid = BandGrid(BandResolution.THIRD_OCTAVE, 40.0, 16000.0)
        assertEquals(grid.size, profile.coverage())
    }

    @Test
    fun `bands above a steep low-pass are not usable and those below are`() {
        val profile = SpectrumProfile.analyze(dull(4.0, 1000.0), rate)
        assertTrue(profile.isUsable(250.0 / 1.12, 250.0 * 1.12))
        assertFalse(profile.isUsable(8000.0 / 1.12, 8000.0 * 1.12))
        assertTrue(profile.coverage() < 20)
    }

    @Test
    fun `the best excerpt is the one with the full spectrum`() {
        // Five seconds of dull audio, then five of full-range noise.
        val dull = dull(5.0, 800.0)
        val full = pink(5.0, seed = 3)
        val joined = AudioBuffer(2, dull.frames + full.frames).apply {
            frames = dull.frames + full.frames
            for (ch in 0 until 2) {
                dull.data[ch].copyInto(data[ch], 0, 0, dull.frames)
                full.data[ch].copyInto(data[ch], dull.frames, 0, full.frames)
            }
        }
        val start = SpectrumProfile.bestExcerptStart(joined, rate, lengthFrames = 3 * rate)
        assertTrue(start >= dull.frames, "picked frame $start, inside the dull part")
    }
}
