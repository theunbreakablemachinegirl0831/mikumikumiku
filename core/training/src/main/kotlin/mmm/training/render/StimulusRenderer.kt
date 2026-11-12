package mmm.training.render

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.LoudnessMatch
import mmm.dsp.SignalGenerator
import mmm.training.ArtifactSpec
import mmm.training.Question
import mmm.training.Stimulus

/** One stimulus, rendered and level-matched, ready to play. */
public data class RenderedStimulus(
    val stimulusId: String,
    val label: String,
    val audio: AudioBuffer,
    /** How much gain the loudness match applied, for the "show your working" panel. */
    val levelCorrectionDb: Double,
)

/** Every stimulus of one question, rendered together against a shared reference. */
public data class RenderedQuestion(
    val questionId: String,
    val sampleRate: Int,
    val stimuli: List<RenderedStimulus>,
) {
    public operator fun get(stimulusId: String): RenderedStimulus? =
        stimuli.firstOrNull { it.stimulusId == stimulusId }
}

/**
 * Renders a question's stimuli offline.
 *
 * The file mode's advantage over live capture is that the whole excerpt can be measured twice, so
 * loudness matching is exact rather than a slewing estimate. Every stimulus is matched against the
 * *same* unprocessed reference rather than against each other, so a three-alternative trial cannot
 * end up with its alternatives level-matched in a chain that drifts.
 *
 * Fades are applied last, after matching, so the fade ramps do not skew the measurement.
 */
public object StimulusRenderer {

    /** 15 ms in and out: long enough to kill switching clicks, short enough not to eat the attack. */
    private const val FADE_MS = 15.0

    public fun render(
        question: Question,
        source: AudioBuffer,
        sampleRate: Int,
        levelMatched: Boolean = true,
    ): RenderedQuestion {
        val format = AudioFormat(sampleRate, source.channels, source.frames)
        val fadeFrames = (FADE_MS / 1000.0 * sampleRate).toInt()

        val rendered = question.stimuli.map { stimulus ->
            val audio = source.copy()
            val processor = stimulus.spec.createProcessor()
            processor.prepare(format)
            processor.process(audio)

            val correction = if (levelMatched && stimulus.spec != ArtifactSpec.None) {
                LoudnessMatch.matchInPlace(audio, source, sampleRate)
            } else {
                0.0
            }

            SignalGenerator.applyFades(audio, fadeFrames)

            RenderedStimulus(
                stimulusId = stimulus.id,
                label = stimulus.label,
                audio = audio,
                levelCorrectionDb = correction,
            )
        }

        return RenderedQuestion(question.id, sampleRate, rendered)
    }

    /** Renders the reference separately, for [Question.reference] on matching trials. */
    public fun renderOne(
        stimulus: Stimulus,
        source: AudioBuffer,
        sampleRate: Int,
        levelMatched: Boolean = true,
    ): RenderedStimulus {
        val format = AudioFormat(sampleRate, source.channels, source.frames)
        val audio = source.copy()
        val processor = stimulus.spec.createProcessor()
        processor.prepare(format)
        processor.process(audio)

        val correction = if (levelMatched && stimulus.spec != ArtifactSpec.None) {
            LoudnessMatch.matchInPlace(audio, source, sampleRate)
        } else {
            0.0
        }
        SignalGenerator.applyFades(audio, (FADE_MS / 1000.0 * sampleRate).toInt())

        return RenderedStimulus(stimulus.id, stimulus.label, audio, correction)
    }
}
