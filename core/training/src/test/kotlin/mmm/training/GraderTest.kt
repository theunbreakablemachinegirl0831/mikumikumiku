package mmm.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraderTest {

    private fun ordinalQuestion(): Question = Question(
        id = "q1",
        family = ExerciseFamily.BAND_ID,
        format = QuestionFormat.IDENTIFY,
        prompt = "which band?",
        stimuli = emptyList(),
        choices = (0..5).map { Choice("b$it", "${it}00 Hz", ordinal = it * 100.0) },
        correctChoiceIds = listOf("b3"),
        level = 0,
        explanation = "+6 dB at 300 Hz",
    )

    @Test
    fun `exact answers score full credit`() {
        val grade = Grader.grade(ordinalQuestion(), Answer("q1", listOf("b3")))
        assertTrue(grade.correct)
        assertEquals(1.0, grade.credit)
        assertEquals(0.0, grade.distance)
    }

    @Test
    fun `near misses on a continuum score partial credit that falls with distance`() {
        val question = ordinalQuestion()
        val one = Grader.grade(question, Answer("q1", listOf("b4")))
        val two = Grader.grade(question, Answer("q1", listOf("b5")))
        val three = Grader.grade(question, Answer("q1", listOf("b0")))

        assertTrue(!one.correct && one.credit > 0.0)
        assertTrue(one.credit > two.credit, "one step (${one.credit}) should beat two (${two.credit})")
        assertTrue(two.credit > three.credit)
        assertEquals(1.0, one.distance)
    }

    @Test
    fun `categorical answers get no near-miss credit`() {
        val question = Question(
            id = "q2",
            family = ExerciseFamily.DISTORTION,
            format = QuestionFormat.IDENTIFY,
            prompt = "what kind?",
            stimuli = emptyList(),
            choices = listOf(Choice("SOFT", "Soft clip"), Choice("HARD", "Hard clip")),
            correctChoiceIds = listOf("SOFT"),
            level = 0,
            explanation = "soft clip",
        )
        val grade = Grader.grade(question, Answer("q2", listOf("HARD")))
        assertEquals(0.0, grade.credit, "adjacent list position is not adjacent distortion character")
    }

    @Test
    fun `ranking credit is the fraction of correctly ordered pairs`() {
        val question = Question(
            id = "q3",
            family = ExerciseFamily.COMPRESSION,
            format = QuestionFormat.RANK,
            prompt = "order them",
            stimuli = emptyList(),
            choices = listOf(Choice("A", "A"), Choice("B", "B"), Choice("C", "C")),
            correctChoiceIds = listOf("A", "B", "C"),
            level = 0,
            explanation = "A=2:1, B=4:1, C=8:1",
        )

        assertEquals(1.0, Grader.grade(question, Answer("q3", listOf("A", "B", "C"))).credit)
        // One swapped neighbour: two of three pairs remain in order.
        assertEquals(2.0 / 3.0, Grader.grade(question, Answer("q3", listOf("B", "A", "C"))).credit, 1e-9)
        assertEquals(0.0, Grader.grade(question, Answer("q3", listOf("C", "B", "A"))).credit)
        // A list that is not a permutation of the stimuli cannot be scored.
        assertEquals(0.0, Grader.grade(question, Answer("q3", listOf("A", "B"))).credit)
    }
}
