package mmm.training

import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.SignalGenerator
import mmm.dsp.LoudnessMatch
import mmm.dsp.LoudnessMeter
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks that the questions the engine asks are actually true of the audio the DSP produces -
 * the seam where a plausible-looking exercise can quietly become unanswerable.
 */
class DspIntegrationTest {

    private val sampleRate = 48000

    private fun probeGainDb(processor: AudioProcessor, frequencyHz: Double): Double {
        val frames = 24000
        val input = SignalGenerator.sine(frequencyHz, frames, sampleRate, channels = 1, amplitude = 0.25f)
        val output = input.copy()
        processor.prepare(AudioFormat(sampleRate, 1, frames))
        processor.process(output)
        val skip = frames / 5
        fun rms(b: mmm.dsp.AudioBuffer): Double {
            var sum = 0.0
            for (i in skip until b.frames) sum += b.data[0][i].toDouble() * b.data[0][i]
            return sqrt(sum / (b.frames - skip)).coerceAtLeast(1e-12)
        }
        return 20.0 * log10(rms(output) / rms(input))
    }

    @Test
    fun `the band a band-ID question calls correct is the band that actually moved`() {
        val generator = Curriculum.generator(ExerciseFamily.BAND_ID)
        for (level in generator.levels.indices) {
            repeat(5) { trial ->
                val question = generator.generate(level, Random(level * 100 + trial))
                val spec = question.stimuli.single { it.spec != ArtifactSpec.None }.spec
                    as ArtifactSpec.BandBoost

                val correct = question.choices.single { it.id == question.correctChoiceIds.single() }
                val centerHz = requireNotNull(correct.ordinal)
                assertTrue(
                    abs(centerHz - spec.band.centerHz) < 1.0,
                    "level $level: answer says ${centerHz} Hz but the filter sits at ${spec.band.centerHz} Hz",
                )

                val measured = probeGainDb(spec.createProcessor(), spec.band.centerHz)
                assertTrue(
                    abs(measured - spec.gainDb) < 0.5,
                    "level $level: claimed ${spec.gainDb} dB, measured $measured dB",
                )
            }
        }
    }

    @Test
    fun `every generated artifact survives loudness matching without being wiped out`() {
        // If matching cancelled the artifact, the exercise would be unanswerable. Check that a
        // matched stimulus is still measurably different from its reference.
        val reference = SignalGenerator.pinkNoise(sampleRate * 3, channels = 2, random = Random(21))
        for (family in Curriculum.families) {
            val generator = Curriculum.generator(family)
            val question = generator.generate(generator.levels.lastIndex / 2, Random(77))
            val spec = question.stimuli.firstOrNull { it.spec != ArtifactSpec.None }?.spec ?: continue

            val processed = reference.copy()
            spec.createProcessor().apply {
                prepare(AudioFormat(sampleRate, 2, processed.frames))
                process(processed)
            }
            LoudnessMatch.matchInPlace(processed, reference, sampleRate)

            val loudnessGap = abs(
                LoudnessMeter.measure(processed, sampleRate) - LoudnessMeter.measure(reference, sampleRate)
            )
            assertTrue(loudnessGap < 0.5, "$family was left $loudnessGap LU off after matching")

            var difference = 0.0
            for (ch in 0 until 2) {
                for (i in 0 until reference.frames) {
                    val d = processed.data[ch][i] - reference.data[ch][i]
                    difference += d.toDouble() * d
                }
            }
            val rmsDifference = sqrt(difference / (reference.frames * 2))
            assertTrue(
                rmsDifference > 1e-4,
                "$family (${spec.description}) is indistinguishable from the reference after matching",
            )
        }
    }
}
