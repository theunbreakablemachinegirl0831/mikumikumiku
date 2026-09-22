package mmm.training

import kotlin.random.Random

/** Produces trials for one [ExerciseFamily] at a requested difficulty. */
public interface ExerciseGenerator {
    public val family: ExerciseFamily

    /** The difficulty ladder, easiest first. */
    public val levels: List<LevelSpec>

    /** @param level index into [levels]; out-of-range values are clamped. */
    public fun generate(level: Int, random: Random): Question
}

internal object GeneratorSupport {

    fun questionId(random: Random): String =
        "q" + random.nextLong().toULong().toString(36).take(10)

    fun letter(index: Int): String = ('A' + index).toString()

    /**
     * Builds an n-alternative forced-choice trial: [alternatives] stimuli of which exactly one
     * carries [artifact], presented in random order.
     */
    fun forcedChoice(
        family: ExerciseFamily,
        level: Int,
        prompt: String,
        artifact: ArtifactSpec,
        alternatives: Int,
        random: Random,
    ): Question {
        val targetIndex = random.nextInt(alternatives)
        val stimuli = (0 until alternatives).map { i ->
            Stimulus(
                id = letter(i),
                label = letter(i),
                spec = if (i == targetIndex) artifact else ArtifactSpec.None,
            )
        }
        return Question(
            id = questionId(random),
            family = family,
            format = QuestionFormat.DETECT,
            prompt = prompt,
            stimuli = stimuli,
            choices = stimuli.map { Choice(it.id, it.label) },
            correctChoiceIds = listOf(letter(targetIndex)),
            level = level,
            explanation = "${letter(targetIndex)}에 처리가 걸려 있었다: ${artifact.description}",
        )
    }

    /**
     * Builds an identification trial: an unprocessed reference plus the processed version, and a
     * list of labelled answers. Keeping the reference playable matters - most of these judgements
     * are comparative, and without an anchor the exercise measures tonal memory instead.
     */
    fun identify(
        family: ExerciseFamily,
        level: Int,
        prompt: String,
        artifact: ArtifactSpec,
        choices: List<Choice>,
        correctChoiceId: String,
        random: Random,
    ): Question = Question(
        id = questionId(random),
        family = family,
        format = QuestionFormat.IDENTIFY,
        prompt = prompt,
        stimuli = listOf(
            Stimulus(id = "ref", label = "원본", spec = ArtifactSpec.None),
            Stimulus(id = "test", label = "처리됨", spec = artifact),
        ),
        choices = choices,
        correctChoiceIds = listOf(correctChoiceId),
        level = level,
        explanation = artifact.description,
    )
}
