package mmm.training

/**
 * The answer a learner is building before they commit it.
 *
 * Most questions take one tap, but ranking takes several and needs undo, and that bookkeeping is
 * the part of the exercise screen that is easy to get subtly wrong - a double-tapped choice
 * entered twice, a removed choice leaving a gap. Keeping it out of the UI means it can be tested
 * here rather than found on a phone.
 */
public class AnswerDraft(public val question: Question) {

    private val picks = mutableListOf<String>()

    private val choiceIds: List<String> = question.choices.map { it.id }

    /** Chosen ids, in the order they were chosen. */
    public val selected: List<String> get() = picks.toList()

    /** True once the answer can be submitted. */
    public val isComplete: Boolean
        get() = if (question.ordered) picks.size == choiceIds.size else picks.size == 1

    /**
     * For ranking: the 1-based position [choiceId] was given, or null if it has not been placed.
     * The screen shows this on the button, so the learner can see the order they are building.
     */
    public fun rankOf(choiceId: String): Int? =
        picks.indexOf(choiceId).takeIf { it >= 0 }?.plus(1)

    /**
     * Single-answer questions: tapping picks, tapping another replaces it.
     * Ranking: tapping an unplaced choice appends it; tapping a placed one takes it back out and
     * closes the gap, so the rest keep their relative order.
     */
    public fun tap(choiceId: String) {
        require(choiceId in choiceIds) { "'$choiceId' is not a choice of ${question.id}" }
        if (!question.ordered) {
            picks.clear()
            picks += choiceId
            return
        }
        if (!picks.remove(choiceId)) picks += choiceId
    }

    /** Takes back the most recent pick. */
    public fun undo() {
        if (picks.isNotEmpty()) picks.removeAt(picks.lastIndex)
    }

    public fun clear() {
        picks.clear()
    }

    /** @throws IllegalStateException if the answer is not complete yet */
    public fun toAnswer(elapsedMs: Long = 0, playCount: Int = 0): Answer {
        check(isComplete) { "answer for ${question.id} is not complete" }
        return Answer(
            questionId = question.id,
            chosenChoiceIds = picks.toList(),
            elapsedMs = elapsedMs,
            playCount = playCount,
        )
    }
}
