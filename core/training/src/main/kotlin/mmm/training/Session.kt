package mmm.training

import kotlin.random.Random

/** The outcome of one graded trial, with everything needed to log or replay it. */
public data class TrialResult(
    val question: Question,
    val answer: Answer,
    val grade: Grade,
    val levelBefore: Int,
    val levelAfter: Int,
) {
    public val leveledUp: Boolean get() = levelAfter > levelBefore
    public val leveledDown: Boolean get() = levelAfter < levelBefore
}

/** Running totals for the session summary screen. */
public data class SessionStats(
    val trials: Int = 0,
    val correct: Int = 0,
    val creditTotal: Double = 0.0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
) {
    public val accuracy: Double get() = if (trials == 0) 0.0 else correct.toDouble() / trials
    public val meanCredit: Double get() = if (trials == 0) 0.0 else creditTotal / trials
}

/**
 * One family's practice session: hands out trials, grades them and moves the level.
 *
 * Deliberately has no idea where the audio comes from. It emits [Question]s whose stimuli are
 * [ArtifactSpec]s, and the playback layer decides whether to apply them to a bundled clip, the
 * learner's own file, or the live captured stream - so a session behaves identically in every mode.
 */
public class TrainingSession(
    public val family: ExerciseFamily,
    private val random: Random = Random.Default,
    startLevel: Int = 0,
    private val generator: ExerciseGenerator = Curriculum.generator(family),
    /** Listener-chosen filter settings, applied to every question this session generates. */
    public val overrides: FilterOverrides = FilterOverrides.NONE,
) {
    public val progression: LevelProgression =
        LevelProgression(maxLevel = generator.levels.lastIndex, startLevel = startLevel)

    public var stats: SessionStats = SessionStats()
        private set

    public val history: MutableList<TrialResult> = mutableListOf()

    public var current: Question? = null
        private set

    public val levelSpec: LevelSpec get() = generator.levels[progression.level]

    /** Generates the next trial at the current level. */
    public fun next(): Question =
        generator.generate(progression.level, random, overrides).also { current = it }

    /**
     * Grades [answer] against the outstanding question and advances the ladder.
     * @throws IllegalStateException if [next] has not been called, or the answer is for a stale question.
     */
    public fun submit(answer: Answer): TrialResult {
        val question = current
            ?: error("submit() called before next()")
        check(answer.questionId == question.id) {
            "answer is for question ${answer.questionId}, but ${question.id} is outstanding"
        }

        val grade = Grader.grade(question, answer)
        val levelBefore = progression.level
        val levelAfter = progression.record(grade.credit)

        val newStreak = if (grade.correct) stats.streak + 1 else 0
        stats = stats.copy(
            trials = stats.trials + 1,
            correct = stats.correct + if (grade.correct) 1 else 0,
            creditTotal = stats.creditTotal + grade.credit,
            streak = newStreak,
            bestStreak = maxOf(stats.bestStreak, newStreak),
        )

        val result = TrialResult(question, answer, grade, levelBefore, levelAfter)
        history += result
        current = null
        return result
    }
}

/** Persistence seam; the Android module backs this with DataStore. */
public interface ProgressStore {
    public fun level(family: ExerciseFamily): Int
    public fun saveLevel(family: ExerciseFamily, level: Int)
    public fun record(result: TrialResult)
}

/** Default store for tests and for a first run before anything has been saved. */
public class InMemoryProgressStore : ProgressStore {
    private val levels = mutableMapOf<ExerciseFamily, Int>()
    public val results: MutableList<TrialResult> = mutableListOf()

    override fun level(family: ExerciseFamily): Int = levels[family] ?: 0
    override fun saveLevel(family: ExerciseFamily, level: Int) {
        levels[family] = level
    }
    override fun record(result: TrialResult) {
        results += result
    }
}
