package mmm.training

import kotlin.math.abs

/**
 * Turns an [Answer] into a [Grade].
 *
 * Identification questions are graded on a scale rather than as pass/fail: on a 1/6-octave grid,
 * naming the band next door is a genuinely different result from picking the wrong end of the
 * spectrum, and collapsing the two throws away the signal the difficulty ladder runs on.
 */
public object Grader {

    /** Credit awarded for missing by one, two, and three choice steps. */
    private val NEAR_MISS_CREDIT = doubleArrayOf(1.0, 0.5, 0.2, 0.05)

    public fun grade(question: Question, answer: Answer): Grade = when (question.format) {
        QuestionFormat.RANK -> gradeRanking(question, answer)
        QuestionFormat.IDENTIFY -> gradeIdentification(question, answer)
        QuestionFormat.DETECT, QuestionFormat.MATCH -> gradeExact(question, answer)
    }

    private fun gradeExact(question: Question, answer: Answer): Grade {
        val correct = answer.chosenChoiceIds.toSet() == question.correctChoiceIds.toSet()
        return Grade(
            correct = correct,
            credit = if (correct) 1.0 else 0.0,
            explanation = question.explanation,
        )
    }

    private fun gradeIdentification(question: Question, answer: Answer): Grade {
        val chosenId = answer.chosenChoiceIds.firstOrNull()
            ?: return Grade(false, 0.0, question.explanation)
        val correctId = question.correctChoiceIds.first()
        if (chosenId == correctId) return Grade(true, 1.0, question.explanation, distance = 0.0)

        val chosenIndex = question.choices.indexOfFirst { it.id == chosenId }
        val correctIndex = question.choices.indexOfFirst { it.id == correctId }
        if (chosenIndex < 0 || correctIndex < 0) {
            return Grade(false, 0.0, question.explanation)
        }

        // Near-miss credit only makes sense when the answers lie on a continuum. "Crossover" is not
        // one step away from "soft clip", so those families are graded strictly right or wrong.
        val ordinal = question.choices.all { it.ordinal != null }
        if (!ordinal) {
            return Grade(false, 0.0, question.explanation)
        }

        val steps = abs(chosenIndex - correctIndex)
        val credit = NEAR_MISS_CREDIT.getOrElse(steps) { 0.0 }
        return Grade(
            correct = false,
            credit = credit,
            explanation = question.explanation,
            distance = steps.toDouble(),
        )
    }

    /**
     * Ranking credit is the fraction of pairs the learner ordered correctly, which degrades
     * gracefully: one swapped neighbour costs much less than a reversed list.
     */
    private fun gradeRanking(question: Question, answer: Answer): Grade {
        val expected = question.correctChoiceIds
        val given = answer.chosenChoiceIds
        if (given.size != expected.size || given.toSet() != expected.toSet()) {
            return Grade(false, 0.0, question.explanation)
        }
        val position = given.withIndex().associate { (i, id) -> id to i }
        var concordant = 0
        var total = 0
        for (i in expected.indices) {
            for (j in i + 1 until expected.size) {
                total++
                val a = position.getValue(expected[i])
                val b = position.getValue(expected[j])
                if (a < b) concordant++
            }
        }
        val credit = if (total == 0) 1.0 else concordant.toDouble() / total
        return Grade(
            correct = credit == 1.0,
            credit = credit,
            explanation = question.explanation,
            distance = (total - concordant).toDouble(),
        )
    }
}
