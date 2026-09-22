package mmm.dsp

import mmm.dsp.artifacts.BandBoostProcessor
import mmm.dsp.artifacts.BandwidthProcessor
import mmm.dsp.artifacts.CompressionProcessor
import mmm.dsp.artifacts.DistortionProcessor
import mmm.dsp.artifacts.DistortionType
import mmm.dsp.artifacts.ResonanceProcessor
import mmm.dsp.artifacts.ReverbProcessor
import mmm.dsp.artifacts.SpectralTiltProcessor
import mmm.dsp.TestSupport.SAMPLE_RATE
import mmm.dsp.TestSupport.probeGainDb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ArtifactProcessorTest {

    @Test
    fun `band boost lifts its own band and leaves distant bands alone`() {
        val grid = BandGrid(BandResolution.THIRD_OCTAVE, lowHz = 63.0, highHz = 8000.0)
        val band = grid.nearest(1000.0)
        val processor = BandBoostProcessor(band, gainDb = 10.0)

        val atCenter = probeGainDb(processor, band.centerHz)
        assertTrue(abs(atCenter - 10.0) < 0.3, "centre gain was $atCenter dB, expected 10")

        val twoOctavesBelow = probeGainDb(processor, band.centerHz / 4)
        val twoOctavesAbove = probeGainDb(processor, band.centerHz * 4)
        assertTrue(abs(twoOctavesBelow) < 1.0, "leakage two octaves below was $twoOctavesBelow dB")
        assertTrue(abs(twoOctavesAbove) < 1.0, "leakage two octaves above was $twoOctavesAbove dB")
    }

    @Test
    fun `finer band grids give correspondingly narrower boosts`() {
        val octaveBand = BandGrid(BandResolution.OCTAVE).nearest(1000.0)
        val sixthBand = BandGrid(BandResolution.SIXTH_OCTAVE).nearest(1000.0)

        val wide = probeGainDb(BandBoostProcessor(octaveBand, 10.0), 1000.0 * 1.26)
        val narrow = probeGainDb(BandBoostProcessor(sixthBand, 10.0), 1000.0 * 1.26)

        assertTrue(wide > narrow + 2.0, "1/1-oct leaked $wide dB, 1/6-oct leaked $narrow dB - expected a clear gap")
    }

    @Test
    fun `resonance is narrow enough to miss a neighbouring third octave`() {
        val processor = ResonanceProcessor(frequencyHz = 2000.0, q = 20.0, gainDb = 9.0)
        assertTrue(abs(probeGainDb(processor, 2000.0) - 9.0) < 0.3)
        // One third of an octave away a Q=20 peak should already be almost gone.
        assertTrue(probeGainDb(processor, 2000.0 * 1.26) < 2.0)
    }

    @Test
    fun `bandwidth limiting attenuates above the high cut and below the low cut`() {
        val processor = BandwidthProcessor(highCutHz = 4000.0, lowCutHz = 200.0, slopeDbPerOctave = 24)

        assertTrue(abs(probeGainDb(processor, 1000.0)) < 0.5, "passband should be flat")
        val oneOctaveAbove = probeGainDb(processor, 8000.0)
        assertTrue(oneOctaveAbove < -18.0, "an octave above the cut was only $oneOctaveAbove dB down")
        val oneOctaveBelow = probeGainDb(processor, 100.0)
        assertTrue(oneOctaveBelow < -18.0, "an octave below the cut was only $oneOctaveBelow dB down")
    }

    @Test
    fun `distortion amount is monotonic in harmonic energy for every type`() {
        for (type in DistortionType.entries) {
            val thds = listOf(0.1, 0.3, 0.6, 0.9).map { amount ->
                TestSupport.measureThd(DistortionProcessor(type, amount), fundamentalBin = 128)
            }
            for (i in 1 until thds.size) {
                assertTrue(
                    thds[i] > thds[i - 1],
                    "$type THD did not increase: ${thds.map { "%.4f".format(it) }}",
                )
            }
        }
    }

    @Test
    fun `distortion strength does not depend on how loud the source is`() {
        // The live-capture mode has no control over the incoming level, so the same difficulty
        // setting has to mean the same audible artifact on a quiet track and a loud one.
        for (type in DistortionType.entries) {
            val quiet = TestSupport.measureThd(DistortionProcessor(type, 0.5), 128, amplitude = 0.2f)
            val loud = TestSupport.measureThd(DistortionProcessor(type, 0.5), 128, amplitude = 0.95f)
            assertTrue(
                abs(quiet - loud) < 0.01,
                "$type varied with level: quiet=${"%.5f".format(quiet)} loud=${"%.5f".format(loud)}",
            )
        }
    }

    @Test
    fun `the bottom of the distortion range stays near threshold`() {
        // A difficulty ladder is useless if its easiest rung is already grossly audible.
        for (type in DistortionType.entries) {
            val subtle = TestSupport.measureThd(DistortionProcessor(type, 0.1), 128)
            val obvious = TestSupport.measureThd(DistortionProcessor(type, 1.0), 128)
            assertTrue(subtle < 0.02, "$type at amount 0.1 was already ${"%.4f".format(subtle)} THD+N")
            assertTrue(obvious > subtle * 3, "$type barely moved across its range")
        }
    }

    @Test
    fun `compression reduces crest factor and more ratio reduces it further`() {
        val source = SignalGenerator.pinkNoise(SAMPLE_RATE, channels = 2, random = kotlin.random.Random(7))
        // Add a loud transient so there is something for the compressor to catch.
        for (ch in 0 until source.channels) {
            for (i in 0 until 480) source.data[ch][SAMPLE_RATE / 2 + i] *= 6f
        }
        val before = TestSupport.crestFactorDb(source)

        fun crestAfter(ratio: Double): Double {
            val buffer = source.copy()
            CompressionProcessor(thresholdDb = -30.0, ratio = ratio, attackMs = 3.0, releaseMs = 100.0).apply {
                prepare(AudioFormat(SAMPLE_RATE, 2, buffer.frames))
                process(buffer)
            }
            return TestSupport.crestFactorDb(buffer)
        }

        val gentle = crestAfter(2.0)
        val heavy = crestAfter(12.0)
        assertTrue(gentle < before, "2:1 did not reduce crest factor ($before -> $gentle dB)")
        assertTrue(heavy < gentle, "12:1 ($heavy dB) should squash more than 2:1 ($gentle dB)")
    }

    @Test
    fun `reverb tail gets longer with decay time`() {
        fun tailEnergy(decaySeconds: Double): Double {
            val frames = SAMPLE_RATE
            val buffer = AudioBuffer(1, frames).apply { this.frames = frames }
            buffer.data[0][0] = 1f
            ReverbProcessor(decaySeconds, mix = 1.0).apply {
                prepare(AudioFormat(SAMPLE_RATE, 1, frames))
                process(buffer)
            }
            // Energy in the last half second only - the tail, not the early reflections.
            var sum = 0.0
            for (i in SAMPLE_RATE / 2 until frames) sum += buffer.data[0][i].toDouble().let { it * it }
            return sum
        }

        val short = tailEnergy(0.3)
        val long = tailEnergy(3.0)
        assertTrue(long > short * 10, "3 s tail ($long) should dwarf a 0.3 s tail ($short)")
    }

    @Test
    fun `spectral tilt trades bass for treble around the pivot`() {
        val bright = SpectralTiltProcessor(tiltDb = 8.0, pivotHz = 1000.0)
        assertTrue(probeGainDb(bright, 8000.0) > 3.0, "bright tilt should lift the top")
        assertTrue(probeGainDb(bright, 100.0) < -3.0, "bright tilt should drop the bottom")

        val dark = SpectralTiltProcessor(tiltDb = -8.0, pivotHz = 1000.0)
        assertTrue(probeGainDb(dark, 8000.0) < -3.0)
        assertTrue(probeGainDb(dark, 100.0) > 3.0)
    }
}
