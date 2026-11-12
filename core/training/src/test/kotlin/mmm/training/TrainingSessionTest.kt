package mmm.training

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrainingSessionTest {

    /** Answers every question correctly by reading the key - a stand-in for a perfect listener. */
    private fun answerCorrectly(question: Question): Answer =
        Answer(question.id, question.correctChoiceIds)

    @Test
    fun `a perfect run climbs the whole ladder and stops at the top`() {
        val session = TrainingSession(ExerciseFamily.BAND_ID, Random(7))
        val maxLevel = Curriculum.maxLevel(ExerciseFamily.BAND_ID)

        repeat(maxLevel * 10 + 20) {
            session.submit(answerCorrectly(session.next()))
        }

        assertEquals(maxLevel, session.progression.level)
        assertEquals(1.0, session.stats.accuracy)
        assertTrue(session.stats.bestStreak >= maxLevel * 10)
    }

    @Test
    fun `stats and history track every trial`() {
        val session = TrainingSession(ExerciseFamily.SPECTRAL_BALANCE, Random(3))
        val first = session.next()
        val wrongChoice = first.choices.first { it.id !in first.correctChoiceIds }
        session.submit(Answer(first.id, listOf(wrongChoice.id)))
        session.submit(answerCorrectly(session.next()))

        assertEquals(2, session.stats.trials)
        assertEquals(1, session.stats.correct)
        assertEquals(2, session.history.size)
        assertEquals(1, session.stats.streak, "the streak restarts after a miss")
    }

    @Test
    fun `answering out of order is rejected`() {
        val session = TrainingSession(ExerciseFamily.REVERB, Random(5))
        assertFailsWith<IllegalStateException> { session.submit(Answer("nope", listOf("d0"))) }

        val question = session.next()
        assertFailsWith<IllegalStateException> { session.submit(Answer("stale", question.correctChoiceIds)) }
        session.submit(answerCorrectly(question))
        assertFailsWith<IllegalStateException> { session.submit(answerCorrectly(question)) }
    }

    @Test
    fun `a session reports the level it is currently teaching`() {
        val session = TrainingSession(ExerciseFamily.DISTORTION, Random(9), startLevel = 2)
        assertEquals(2, session.progression.level)
        assertEquals(2, session.levelSpec.index)
        assertEquals(2, session.next().level)
    }
}
