package mmm.training

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.SignalGenerator
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilterOverridesTest {

    private val sampleRate = 48000

    private fun gainAt(processor: AudioProcessor, hz: Double): Double {
        val frames = 24000
        val input = SignalGenerator.sine(hz, frames, sampleRate, channels = 1, amplitude = 0.25f)
        val output = input.copy()
        processor.prepare(AudioFormat(sampleRate, 1, frames))
        processor.process(output)
        fun rms(b: AudioBuffer): Double {
            var sum = 0.0
            for (i in frames / 5 until frames) sum += b.data[0][i].toDouble() * b.data[0][i]
            return sqrt(sum / (frames - frames / 5))
        }
        return 20 * log10(rms(output) / rms(input))
    }

    private fun bandSpecs(level: Int, overrides: FilterOverrides, trials: Int = 30): List<ArtifactSpec.BandBoost> {
        val generator = Curriculum.generator(ExerciseFamily.BAND_ID)
        return (0 until trials).map { seed ->
            generator.generate(level, Random(seed), overrides)
                .stimuli.single { it.spec != ArtifactSpec.None }.spec as ArtifactSpec.BandBoost
        }
    }

    @Test
    fun `a gain override replaces the ladder's boost and keeps cuts as cuts`() {
        // Level 2 mixes boosts and cuts at 9 dB on the ladder.
        val specs = bandSpecs(level = 2, overrides = FilterOverrides(gainDb = 4.0))
        assertTrue(specs.all { abs(it.gainDb) == 4.0 }, "every question should use 4 dB: ${specs.map { it.gainDb }}")
        assertTrue(specs.any { it.gainDb < 0 }, "cut questions must stay cuts")
        assertTrue(specs.any { it.gainDb > 0 })
    }

    @Test
    fun `without overrides the ladder is untouched`() {
        val specs = bandSpecs(level = 0, overrides = FilterOverrides.NONE)
        assertTrue(specs.all { it.gainDb == 12.0 && it.q == null })
    }

    @Test
    fun `the band filter actually plays the gain and Q it was given`() {
        val spec = bandSpecs(level = 0, overrides = FilterOverrides(gainDb = 5.0, q = 8.0)).first { it.gainDb > 0 }
        val center = spec.band.centerHz

        assertTrue(abs(gainAt(spec.createProcessor(), center) - 5.0) < 0.3, "centre gain should be 5 dB")

        // A narrow Q 8 bump is nearly gone half an octave away; the octave grid's own Q is not.
        val narrow = gainAt(spec.createProcessor(), center * 1.41)
        val ladderDefault = ArtifactSpec.BandBoost(spec.band, 5.0).createProcessor()
        val wide = gainAt(ladderDefault, center * 1.41)
        assertTrue(narrow < wide - 1.0, "Q 8 leaked $narrow dB, the band's own width leaked $wide dB")
    }

    @Test
    fun `resonance takes both overrides`() {
        val generator = Curriculum.generator(ExerciseFamily.RESONANCE)
        val question = generator.generate(level = 0, random = Random(3), overrides = FilterOverrides(gainDb = 3.0, q = 30.0))
        val spec = question.stimuli.single { it.spec != ArtifactSpec.None }.spec as ArtifactSpec.Resonance
        assertEquals(3.0, spec.gainDb)
        assertEquals(30.0, spec.q)
    }

    @Test
    fun `the explanation reports the filter that was played`() {
        val question = Curriculum.generator(ExerciseFamily.BAND_ID)
            .generate(0, Random(1), FilterOverrides(gainDb = 3.0, q = 6.0))
        assertTrue("3 dB" in question.explanation, question.explanation)
        assertTrue("Q 6" in question.explanation, question.explanation)
    }

    @Test
    fun `a session applies its overrides to every question`() {
        val session = TrainingSession(
            family = ExerciseFamily.BAND_ID,
            random = Random(4),
            overrides = FilterOverrides(gainDb = 2.0),
        )
        repeat(15) {
            val question = session.next()
            val spec = question.stimuli.single { it.spec != ArtifactSpec.None }.spec as ArtifactSpec.BandBoost
            assertEquals(2.0, abs(spec.gainDb))
            session.submit(Answer(question.id, question.correctChoiceIds))
        }
    }

    @Test
    fun `nonsense values are refused`() {
        assertFailsWith<IllegalArgumentException> { FilterOverrides(gainDb = -3.0) }
        assertFailsWith<IllegalArgumentException> { FilterOverrides(q = 0.0) }
    }
}
