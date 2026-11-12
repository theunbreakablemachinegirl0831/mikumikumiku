package mmm.training.audiometry

import kotlin.random.Random

/**
 * One presentation of the counting test: [count] tone pulses, each starting at one of
 * [onsetsMs], somewhere in a window of [CountingProtocol.WINDOW_MS].
 */
public data class CountingTrial(val count: Int, val onsetsMs: List<Long>) {
    init {
        require(count in CountingProtocol.MIN_COUNT..CountingProtocol.MAX_COUNT) { "count $count out of range" }
        require(onsetsMs.size == count) { "one onset per pulse" }
    }

    /** Whether a pulse is sounding [atMs] into the window. */
    public fun pulseAt(atMs: Double): Boolean = onsetsMs.any { atMs >= it && atMs < it + CountingProtocol.PULSE_MS }
}

/** What an answer to a [CountingTrial] says. */
public data class CountingJudgement(
    /** The count was right: every pulse was heard. */
    val heard: Boolean,
    /** More pulses were reported than played: something was "heard" that was not there. */
    val falseAlarm: Boolean,
)

/**
 * The counting form of pure-tone audiometry: instead of pressing whenever a tone is heard, the
 * listener hears a five-second window holding one to five pulses at random moments, and then
 * says how many there were.
 *
 * It asks for a fact rather than a reaction, so it does not depend on reaction time or on the
 * app catching a press at the right moment; and guessing does not pay - a listener who hears
 * nothing and guesses is right one time in six, where pressing on a hunch in the classic form is
 * right half the time. A count is taken as heard only when it is exact: near threshold some
 * pulses are missed, and that is exactly the level the search should not settle on.
 */
public object CountingProtocol {
    public const val WINDOW_MS: Long = 5000
    public const val PULSE_MS: Long = 250
    public const val MIN_COUNT: Int = 1
    public const val MAX_COUNT: Int = 5
    /** The answers offered: 0 (heard nothing) to [MAX_COUNT]. */
    public val ANSWERS: IntRange = 0..MAX_COUNT

    /** Silence between one pulse's end and the next one's start, at the least: pulses must be countable. */
    public const val MIN_GAP_MS: Long = 350
    /** Silence at either end of the window, so the first pulse is not lost in the stream starting. */
    public const val EDGE_MS: Long = 200

    public fun trial(random: Random): CountingTrial {
        val count = random.nextInt(MIN_COUNT, MAX_COUNT + 1)
        // The free time beyond the minimum gaps, shared out at random among the count + 1 spaces
        // around the pulses: every arrangement that keeps the gaps is possible.
        val slack = WINDOW_MS - 2 * EDGE_MS - count * PULSE_MS - (count - 1) * MIN_GAP_MS
        val weights = DoubleArray(count + 1) { random.nextDouble() + 1e-9 }
        val total = weights.sum()
        var at = EDGE_MS.toDouble()
        val onsets = ArrayList<Long>(count)
        for (i in 0 until count) {
            at += slack * weights[i] / total
            onsets += at.toLong()
            at += PULSE_MS + MIN_GAP_MS
        }
        return CountingTrial(count, onsets)
    }

    public fun judge(trial: CountingTrial, answer: Int): CountingJudgement =
        CountingJudgement(heard = answer == trial.count, falseAlarm = answer > trial.count)
}
