package mmm.training

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contract every generator must satisfy, checked across every family and every level. */
class GeneratorContractTest {

    private fun eachLevel(block: (ExerciseFamily, Int, Question) -> Unit) {
        for (family in Curriculum.families) {
            val generator = Curriculum.generator(family)
            for (level in generator.levels.indices) {
                repeat(25) { trial ->
                    block(family, level, generator.generate(level, Random(level * 1000 + trial)))
                }
            }
        }
    }

    @Test
    fun `every generated question is answerable`() {
        eachLevel { family, level, question ->
            val where = "$family level $level"
            assertTrue(question.stimuli.isNotEmpty(), "$where produced nothing to play")
            assertTrue(question.choices.isNotEmpty(), "$where produced no answers")
            assertTrue(question.correctChoiceIds.isNotEmpty(), "$where has no correct answer")

            val choiceIds = question.choices.map { it.id }
            assertEquals(choiceIds.size, choiceIds.toSet().size, "$where has duplicate choice ids")
            for (id in question.correctChoiceIds) {
                assertTrue(id in choiceIds, "$where marks '$id' correct but it is not a choice")
            }
            assertTrue(question.explanation.isNotBlank(), "$where has no explanation to show afterwards")
            assertEquals(family, question.family)
            assertEquals(level, question.level)
        }
    }

    @Test
    fun `only ranking questions have more than one correct answer`() {
        eachLevel { family, level, question ->
            if (question.format != QuestionFormat.RANK) {
                assertEquals(
                    1,
                    question.correctChoiceIds.size,
                    "$family level $level is ${question.format} but has multiple correct answers",
                )
            }
        }
    }

    @Test
    fun `forced-choice trials have exactly one processed stimulus`() {
        eachLevel { family, level, question ->
            if (question.format == QuestionFormat.DETECT) {
                val processed = question.stimuli.count { it.spec != ArtifactSpec.None }
                assertEquals(1, processed, "$family level $level had $processed processed stimuli")
                val target = question.correctChoiceIds.single()
                val targetStimulus = question.stimuli.single { it.id == target }
                assertTrue(
                    targetStimulus.spec != ArtifactSpec.None,
                    "$family level $level points at a clean stimulus as the answer",
                )
            }
        }
    }

    @Test
    fun `identification trials always offer an unprocessed reference to compare against`() {
        eachLevel { family, level, question ->
            if (question.format == QuestionFormat.IDENTIFY) {
                assertTrue(
                    question.stimuli.any { it.spec == ArtifactSpec.None },
                    "$family level $level gives nothing to compare against",
                )
            }
        }
    }

    @Test
    fun `generation is reproducible from its seed`() {
        for (family in Curriculum.families) {
            val generator = Curriculum.generator(family)
            for (level in generator.levels.indices) {
                val first = generator.generate(level, Random(42))
                val second = generator.generate(level, Random(42))
                assertEquals(first, second, "$family level $level is not reproducible")
            }
        }
    }

    @Test
    fun `everything the learner reads is in Korean`() {
        // These strings go straight onto the screen. English technical terms are welcome in
        // brackets, but every line has to carry Korean, or a translated app ships half-English.
        val hangul = Regex("[가-힣]")
        eachLevel { family, level, question ->
            val where = "$family level $level"
            assertTrue(hangul.containsMatchIn(question.prompt), "$where prompt: ${question.prompt}")
            assertTrue(hangul.containsMatchIn(question.explanation), "$where explanation: ${question.explanation}")
        }
        for (family in Curriculum.families) {
            assertTrue(hangul.containsMatchIn(family.displayName), "$family has no Korean name")
            Curriculum.levels(family).forEach {
                assertTrue(hangul.containsMatchIn(it.name + it.description), "$family ${it.name}")
            }
        }
    }

    @Test
    fun `every family has a non-trivial ladder with described levels`() {
        for (family in Curriculum.families) {
            val levels = Curriculum.levels(family)
            assertTrue(levels.size >= 5, "$family only has ${levels.size} levels")
            levels.forEachIndexed { i, spec ->
                assertEquals(i, spec.index, "$family level $i is mis-indexed")
                assertTrue(spec.name.isNotBlank() && spec.description.isNotBlank())
            }
        }
    }
}
