package mmm.app.ui.exercise

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.training.AnswerDraft
import mmm.training.Curriculum
import mmm.training.ExerciseFamily
import mmm.training.FilterOverrides
import mmm.training.Question
import mmm.training.SessionStats
import mmm.training.TrainingSession
import mmm.training.TrialResult
import kotlin.random.Random

data class ExerciseUiState(
    val family: ExerciseFamily,
    /** Playing to live music rather than a rendered excerpt; changes what "stop" means. */
    val live: Boolean = false,
    /** Non-null while something is being prepared, with what to tell the learner. */
    val busy: String? = "음원을 준비하는 중",
    val error: String? = null,
    val sourceLabel: String = "",
    val question: Question? = null,
    val level: Int = 0,
    val levelCount: Int = 1,
    val levelName: String = "",
    val levelDescription: String = "",
    /**
     * Set when the listener's own filter settings replace the ladder's, because the level
     * description still quotes the ladder's numbers and would otherwise say the wrong thing.
     */
    val filterNote: String? = null,
    val playingId: String? = null,
    val picks: List<String> = emptyList(),
    val canSubmit: Boolean = false,
    val result: TrialResult? = null,
    val stats: SessionStats = SessionStats(),
)

/**
 * Runs one family's practice: question, prepare, listen, answer, grade, repeat.
 *
 * Preparing happens off the main thread because in the file mode it is real work - every stimulus
 * is filtered and then loudness-measured twice - and a question is only shown once all of its
 * stimuli are ready, so the learner can never press a button whose audio does not exist yet.
 *
 * @param live play stimuli as processing on the live capture instead of rendered clips
 */
class ExerciseViewModel(
    private val app: MmmApplication,
    private val family: ExerciseFamily,
    private val live: Boolean,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ExerciseUiState(family = family, live = live, busy = if (live) "실시간 연결을 확인하는 중" else "음원을 준비하는 중")
    )
    val ui: StateFlow<ExerciseUiState> = _ui.asStateFlow()

    private var backend: StimulusBackend? = null
    private var session: TrainingSession? = null
    private var draft: AnswerDraft? = null
    private var playingId: String? = null

    private var questionShownAt = 0L
    private var playCount = 0

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        try {
            val settings = app.settings.current()

            // Starting from a stale level would put the learner back on a rung they already left.
            app.progress.awaitLoaded()

            val opened = if (live) LiveBackend(app.live, settings.loudnessMatched) else FileBackend(app, settings)
            opened.open()
            backend = opened
            val overrides = if (family in FilterOverrides.APPLIES_TO) {
                settings.filterOverrides()
            } else {
                FilterOverrides.NONE
            }
            session = TrainingSession(
                family = family,
                random = Random(System.nanoTime()),
                startLevel = app.progress.level(family),
                overrides = overrides,
            )
            _ui.value = _ui.value.copy(
                sourceLabel = opened.sourceLabel,
                error = null,
                filterNote = describe(overrides),
            )
            nextQuestion()
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName
            _ui.value = _ui.value.copy(
                busy = null,
                error = if (live) {
                    "실시간 과제를 시작하지 못했다: $reason"
                } else {
                    "음원을 불러오지 못했다: $reason. 홈에서 핑크 노이즈를 고르거나 다른 파일을 선택한다."
                },
            )
        }
    }

    /** Plays [stimulusId], or stops if it is already the one playing. */
    fun toggleStimulus(stimulusId: String) {
        val current = backend ?: return
        if (playingId == stimulusId) {
            current.stop()
            playingId = null
        } else {
            current.play(stimulusId)
            playingId = stimulusId
            playCount++
        }
        _ui.value = _ui.value.copy(playingId = playingId)
    }

    fun stopPlayback() {
        backend?.stop()
        playingId = null
        _ui.value = _ui.value.copy(playingId = null)
    }

    fun tapChoice(choiceId: String) {
        if (_ui.value.result != null) return
        val current = draft ?: return
        current.tap(choiceId)
        publishDraft(current)
    }

    fun undo() {
        val current = draft ?: return
        current.undo()
        publishDraft(current)
    }

    fun submit() {
        val current = draft ?: return
        val running = session ?: return
        if (!current.isComplete || _ui.value.result != null) return

        val answer = current.toAnswer(
            elapsedMs = SystemClock.elapsedRealtime() - questionShownAt,
            playCount = playCount,
        )
        val result = running.submit(answer)
        app.progress.record(result)
        app.progress.saveLevel(family, result.levelAfter)

        _ui.value = _ui.value.copy(result = result, stats = running.stats, canSubmit = false)
    }

    fun next() {
        viewModelScope.launch { nextQuestion() }
    }

    private suspend fun nextQuestion() {
        val running = session ?: return
        val current = backend ?: return
        current.stop()
        playingId = null
        _ui.value = _ui.value.copy(busy = "문제를 만드는 중", result = null, playingId = null)

        val question = running.next()
        current.prepare(question)

        val newDraft = AnswerDraft(question)
        draft = newDraft
        questionShownAt = SystemClock.elapsedRealtime()
        playCount = 0

        val levels = Curriculum.levels(family)
        val spec = running.levelSpec
        _ui.value = _ui.value.copy(
            busy = null,
            question = question,
            level = running.progression.level,
            levelCount = levels.size,
            levelName = spec.name,
            levelDescription = spec.description,
            picks = emptyList(),
            canSubmit = false,
            stats = running.stats,
        )
    }

    private fun publishDraft(current: AnswerDraft) {
        _ui.value = _ui.value.copy(picks = current.selected, canSubmit = current.isComplete)
    }

    private fun describe(overrides: FilterOverrides): String? {
        if (!overrides.isActive) return null
        val parts = listOfNotNull(
            overrides.gainDb?.let { "게인 ±${trim(it)} dB" },
            overrides.q?.let { "Q ${trim(it)}" },
        )
        return "사용자 지정 필터: ${parts.joinToString(" · ")} - 레벨 설명의 값 대신 적용된다"
    }

    private fun trim(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()

    /** Ranking needs the order on the buttons; everything else just needs "is it picked". */
    fun rankOf(choiceId: String): Int? = draft?.rankOf(choiceId)

    override fun onCleared() {
        backend?.release()
        super.onCleared()
    }

    companion object {
        fun factory(app: MmmApplication, family: ExerciseFamily, live: Boolean): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ExerciseViewModel(app, family, live) }
            }
    }
}
