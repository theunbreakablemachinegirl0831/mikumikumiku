package mmm.training

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerDraftTest {

    private fun identify(): Question =
        Curriculum.generator(ExerciseFamily.BAND_ID).generate(0, Random(1))

    private fun ranking(): Question {
        // Compression level 4 and up are ranking questions.
        val question = Curriculum.generator(ExerciseFamily.COMPRESSION).generate(4, Random(2))
        check(question.format == QuestionFormat.RANK)
        return question
    }

    @Test
    fun `a single-answer question takes one pick and a second tap replaces it`() {
        val question = identify()
        val draft = AnswerDraft(question)
        val (first, second) = question.choices.take(2).map { it.id }

        assertTrue(!draft.isComplete)
        draft.tap(first)
        assertTrue(draft.isComplete)
        draft.tap(second)
        assertEquals(listOf(second), draft.selected, "changing your mind must not add a second answer")
    }

    @Test
    fun `ranking builds an order and is only complete when every stimulus is placed`() {
        val question = ranking()
        val draft = AnswerDraft(question)
        val ids = question.choices.map { it.id }

        ids.dropLast(1).forEach(draft::tap)
        assertTrue(!draft.isComplete)
        draft.tap(ids.last())
        assertTrue(draft.isComplete)
        assertEquals(ids, draft.selected)
        assertEquals(1, draft.rankOf(ids.first()))
        assertEquals(ids.size, draft.rankOf(ids.last()))
    }

    @Test
    fun `tapping a placed choice takes it out and closes the gap`() {
        val question = ranking()
        val draft = AnswerDraft(question)
        val (a, b, c) = question.choices.map { it.id }

        draft.tap(a); draft.tap(b); draft.tap(c)
        draft.tap(b)
        assertEquals(listOf(a, c), draft.selected)
        assertNull(draft.rankOf(b))
        assertEquals(2, draft.rankOf(c), "later picks move up rather than leaving a hole")
    }

    @Test
    fun `undo takes back the most recent pick only`() {
        val question = ranking()
        val draft = AnswerDraft(question)
        val (a, b) = question.choices.map { it.id }

        draft.undo() // harmless when empty
        draft.tap(a); draft.tap(b)
        draft.undo()
        assertEquals(listOf(a), draft.selected)
    }

    @Test
    fun `the finished draft grades the same as a hand-built answer`() {
        val question = ranking()
        val draft = AnswerDraft(question)
        question.correctChoiceIds.forEach(draft::tap)

        val grade = Grader.grade(question, draft.toAnswer())
        assertTrue(grade.correct)
        assertEquals(1.0, grade.credit)
    }

    @Test
    fun `an incomplete draft cannot be submitted and a foreign id is rejected`() {
        val draft = AnswerDraft(ranking())
        assertFailsWith<IllegalStateException> { draft.toAnswer() }
        assertFailsWith<IllegalArgumentException> { draft.tap("not-a-choice") }
    }
}
