package mmm.training

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.BiquadCascade
import mmm.dsp.BiquadDesign
import mmm.dsp.SignalGenerator
import mmm.dsp.SpectrumProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class PracticeFocusTest {

    private val bandId = Curriculum.generator(ExerciseFamily.BAND_ID)
    private val resonance = Curriculum.generator(ExerciseFamily.RESONANCE)

    @Test
    fun `band questions stay inside the chosen range at every level`() {
        for ((_, range) in FrequencyRange.PRESETS) {
            val focus = PracticeFocus(range = range)
            for (level in bandId.levels.indices) {
                repeat(20) { seed ->
                    val question = bandId.generate(level, Random(seed), focus = focus)
                    val spec = question.stimuli.first { it.id == "test" }.spec as ArtifactSpec.BandBoost
                    assertTrue(question.choices.size >= 2, "${range.label} level $level: ${question.choices.size} choices")
                    // A range narrower than two bands is widened by one band each side.
                    val step = 2.0
                    assertTrue(
                        spec.band.centerHz >= range.lowHz / step && spec.band.centerHz <= range.highHz * step,
                        "${range.label} level $level gave ${spec.band.label}",
                    )
                }
            }
        }
    }

    @Test
    fun `resonance questions stay inside the chosen range`() {
        val range = FrequencyRange(500.0, 2000.0)
        repeat(50) { seed ->
            val question = resonance.generate(2, Random(seed), focus = PracticeFocus(range = range))
            val spec = question.stimuli.map { it.spec }.filterIsInstance<ArtifactSpec.Resonance>().single()
            assertTrue(spec.frequencyHz in 500.0..2000.0, "${spec.frequencyHz} Hz")
        }
    }

    @Test
    fun `band questions avoid bands the audio cannot carry`() {
        // Audio with nothing above about 1 kHz: no question may boost the treble.
        val rate = 48000
        val audio: AudioBuffer = SignalGenerator.pinkNoise(rate * 3, channels = 2, random = Random(5))
        val cascade = BiquadCascade(8, 2)
        repeat(8) { cascade.setCoefficients(it, BiquadDesign.lowPass(1000.0, 0.707, rate)) }
        cascade.prepare(AudioFormat(rate, 2, audio.frames))
        cascade.process(audio)
        val focus = PracticeFocus(spectrum = SpectrumProfile.analyze(audio, rate))

        repeat(60) { seed ->
            val question = bandId.generate(1, Random(seed), focus = focus)
            val spec = question.stimuli.first { it.id == "test" }.spec as ArtifactSpec.BandBoost
            assertTrue(spec.band.centerHz < 4000.0, "boosted ${spec.band.label}, where there is nothing")
            assertTrue(question.choices.any { it.label.contains("16") }, "the answer grid should still show every band")
        }
    }

    @Test
    fun `range labels read naturally`() {
        assertTrue(FrequencyRange(500.0, 2000.0).label == "500 Hz–2 kHz")
        assertTrue(FrequencyRange(1600.0, 5000.0).label == "1.6 kHz–5 kHz")
    }
}
