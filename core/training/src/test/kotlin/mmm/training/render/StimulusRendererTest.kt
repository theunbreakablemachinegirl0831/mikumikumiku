package mmm.training.render

import mmm.dsp.LoudnessMeter
import mmm.dsp.SignalGenerator
import mmm.training.ArtifactSpec
import mmm.training.Curriculum
import mmm.training.ExerciseFamily
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StimulusRendererTest {

    private val sampleRate = 48000
    private val source = SignalGenerator.pinkNoise(sampleRate * 3, channels = 2, random = Random(5))

    @Test
    fun `every stimulus of a question is rendered`() {
        val question = Curriculum.generator(ExerciseFamily.RESONANCE).generate(3, Random(1))
        val rendered = StimulusRenderer.render(question, source, sampleRate)

        assertEquals(question.stimuli.size, rendered.stimuli.size)
        for (stimulus in question.stimuli) {
            val match = rendered[stimulus.id]
            assertTrue(match != null, "stimulus ${stimulus.id} was not rendered")
            assertEquals(source.frames, match.audio.frames)
        }
    }

    @Test
    fun `processed stimuli end up level-matched to the reference`() {
        val question = Curriculum.generator(ExerciseFamily.BAND_ID).generate(2, Random(9))
        val rendered = StimulusRenderer.render(question, source, sampleRate)

        val referenceLufs = LoudnessMeter.measure(source, sampleRate)
        for (stimulus in rendered.stimuli) {
            val lufs = LoudnessMeter.measure(stimulus.audio, sampleRate)
            assertTrue(
                abs(lufs - referenceLufs) < 0.6,
                "${stimulus.stimulusId} sits ${lufs - referenceLufs} LU from the reference",
            )
        }
    }

    @Test
    fun `each stimulus is matched against the reference, not against the previous one`() {
        // Chaining corrections would let error accumulate across a multi-alternative trial.
        val question = Curriculum.generator(ExerciseFamily.SPECTRAL_BALANCE).generate(1, Random(4))
        val rendered = StimulusRenderer.render(question, source, sampleRate)
        val clean = rendered.stimuli.filter {
            question.stimuli.first { s -> s.id == it.stimulusId }.spec == ArtifactSpec.None
        }
        clean.forEach {
            assertEquals(0.0, it.levelCorrectionDb, "an unprocessed stimulus needs no correction")
        }
    }

    @Test
    fun `fades are applied so switching between stimuli cannot click`() {
        val question = Curriculum.generator(ExerciseFamily.DISTORTION).generate(0, Random(2))
        val rendered = StimulusRenderer.render(question, source, sampleRate)
        for (stimulus in rendered.stimuli) {
            // Tolerance rather than equality: a negative sample scaled by a zero ramp lands on
            // -0.0f, which boxed Float equality does not consider equal to 0f.
            assertEquals(0f, stimulus.audio.data[0][0], 1e-7f, "the first sample must be silent")
            assertEquals(
                0f,
                stimulus.audio.data[0][stimulus.audio.frames - 1],
                1e-7f,
                "the last sample must be silent",
            )
            // And the ramp has to actually be a ramp, not a single silent sample.
            val quarterIn = stimulus.audio.data[0][100]
            val settled = stimulus.audio.data[0][stimulus.audio.frames / 2]
            assertTrue(
                kotlin.math.abs(quarterIn) < kotlin.math.abs(settled) * 4,
                "the fade-in does not look like a ramp",
            )
        }
    }

    @Test
    fun `matching can be turned off for demonstrations`() {
        val question = Curriculum.generator(ExerciseFamily.BAND_ID).generate(0, Random(11))
        val rendered = StimulusRenderer.render(question, source, sampleRate, levelMatched = false)
        rendered.stimuli.forEach { assertEquals(0.0, it.levelCorrectionDb) }
    }
}
