package mmm.dsp

import mmm.dsp.artifacts.BandBoostProcessor
import mmm.dsp.artifacts.SpectralTiltProcessor
import mmm.dsp.TestSupport.SAMPLE_RATE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LoudnessTest {

    private fun pink(seconds: Double = 3.0, seed: Int = 11): AudioBuffer =
        SignalGenerator.pinkNoise(
            frames = (SAMPLE_RATE * seconds).toInt(),
            channels = 2,
            random = kotlin.random.Random(seed),
        )

    @Test
    fun `halving the amplitude drops the measurement by six LU`() {
        val loud = pink()
        val quiet = loud.copy().also { b ->
            for (ch in 0 until b.channels) for (i in 0 until b.frames) b.data[ch][i] *= 0.5f
        }
        val delta = LoudnessMeter.measure(loud, SAMPLE_RATE) - LoudnessMeter.measure(quiet, SAMPLE_RATE)
        assertTrue(abs(delta - 6.02) < 0.1, "expected 6.02 LU, measured $delta")
    }

    @Test
    fun `silence gates out entirely`() {
        val silence = AudioBuffer(2, SAMPLE_RATE).apply { frames = SAMPLE_RATE }
        assertTrue(!LoudnessMeter.measure(silence, SAMPLE_RATE).isFinite())
    }

    @Test
    fun `a boosted band is measurably louder before matching and level after it`() {
        val reference = pink()
        val processed = reference.copy()
        val band = BandGrid(BandResolution.OCTAVE).nearest(500.0)
        BandBoostProcessor(band, gainDb = 12.0).apply {
            prepare(AudioFormat(SAMPLE_RATE, 2, processed.frames))
            process(processed)
        }

        val before = LoudnessMeter.measure(processed, SAMPLE_RATE) -
            LoudnessMeter.measure(reference, SAMPLE_RATE)
        assertTrue(before > 1.0, "a 12 dB octave boost should raise loudness, but it moved $before LU")

        val correction = LoudnessMatch.matchInPlace(processed, reference, SAMPLE_RATE)
        assertTrue(abs(correction - (-before)) < 0.2, "correction $correction should undo $before")

        val after = LoudnessMeter.measure(processed, SAMPLE_RATE) -
            LoudnessMeter.measure(reference, SAMPLE_RATE)
        assertTrue(
            abs(after) < 0.3,
            "after matching the stimuli should be within 0.3 LU, but differ by $after",
        )
    }

    @Test
    fun `realtime matcher converges on a steady tilt`() {
        val tilt = SpectralTiltProcessor(tiltDb = 10.0, pivotHz = 1000.0)
        val matcher = RealtimeLevelMatch(tilt, maxSlewDbPerSecond = 24.0)
        val blockSize = 1024
        matcher.prepare(AudioFormat(SAMPLE_RATE, 2, blockSize))

        val source = pink(seconds = 8.0, seed = 3)
        val processed = source.copy()
        val block = AudioBuffer(2, blockSize)
        var start = 0
        while (start + blockSize <= processed.frames) {
            block.frames = blockSize
            for (ch in 0 until 2) processed.data[ch].copyInto(block.data[ch], 0, start, start + blockSize)
            matcher.process(block)
            for (ch in 0 until 2) block.data[ch].copyInto(processed.data[ch], start, 0, blockSize)
            start += blockSize
        }

        // Compare the settled second half only; the first seconds are the matcher slewing into place.
        val half = processed.frames / 2
        fun tail(buffer: AudioBuffer): AudioBuffer =
            AudioBuffer(2, half).also { out ->
                out.frames = half
                for (ch in 0 until 2) buffer.data[ch].copyInto(out.data[ch], 0, half, half + half)
            }

        val delta = LoudnessMeter.measure(tail(processed), SAMPLE_RATE) -
            LoudnessMeter.measure(tail(source), SAMPLE_RATE)
        assertTrue(abs(delta) < 1.5, "live matcher left a $delta LU offset")
    }
}
