package mmm.app.ui.exercise

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mmm.app.MmmApplication
import mmm.app.playback.StimulusPlayer
import mmm.app.source.LoadedSource
import mmm.app.source.StimulusSource
import mmm.training.AnswerDraft
import mmm.training.Curriculum
import mmm.training.ExerciseFamily
import mmm.training.Question
import mmm.training.SessionStats
import mmm.training.TrainingSession
import mmm.training.TrialResult
import mmm.training.render.StimulusRenderer
import kotlin.random.Random

data class ExerciseUiState(
    val family: ExerciseFamily,
    /** Non-null while something is being prepared, with what to tell the learner. */
    val busy: String? = "음원을 준비하는 중",
    val error: String? = null,
    val sourceLabel: String = "",
    val question: Question? = null,
    val level: Int = 0,
    val levelCount: Int = 1,
    val levelName: String = "",
    val levelDescription: String = "",
    val playingId: String? = null,
    val picks: List<String> = emptyList(),
    val canSubmit: Boolean = false,
    val result: TrialResult? = null,
    val stats: SessionStats = SessionStats(),
)

/**
 * Runs one family's practice: question, render, listen, answer, grade, repeat.
 *
 * Rendering happens off the main thread because it is real work - every stimulus is filtered and
 * then loudness-measured twice - and a question is only shown once all of its stimuli are ready,
 * so the learner can never press a button whose audio does not exist yet.
 */
class ExerciseViewModel(
    private val app: MmmApplication,
    private val family: ExerciseFamily,
) : ViewModel() {

    private val _ui = MutableStateFlow(ExerciseUiState(family = family))
    val ui: StateFlow<ExerciseUiState> = _ui.asStateFlow()

    private val player = StimulusPlayer()
    private var session: TrainingSession? = null
    private var source: LoadedSource? = null
    private var draft: AnswerDraft? = null
    private var loudnessMatched = true

    private var questionShownAt = 0L
    private var playCount = 0

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        try {
            val settings = app.settings.current()
            loudnessMatched = settings.loudnessMatched

            // Starting from a stale level would put the learner back on a rung they already left.
            app.progress.awaitLoaded()

            val loaded = withContext(Dispatchers.IO) {
                StimulusSource.load(app, settings.source, settings.excerptSeconds)
            }
            source = loaded
            session = TrainingSession(
                family = family,
                random = Random(System.nanoTime()),
                startLevel = app.progress.level(family),
            )
            _ui.value = _ui.value.copy(sourceLabel = loaded.label, error = null)
            nextQuestion()
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                busy = null,
                error = "음원을 불러오지 못했다: ${e.message ?: e::class.simpleName}. " +
                    "홈에서 핑크 노이즈를 고르거나 다른 파일을 선택한다.",
            )
        }
    }

    /** Plays [stimulusId], or stops if it is already the one playing. */
    fun toggleStimulus(stimulusId: String) {
        if (player.playingId == stimulusId) {
            player.stop()
        } else {
            player.play(stimulusId)
            playCount++
        }
        _ui.value = _ui.value.copy(playingId = player.playingId)
    }

    fun stopPlayback() {
        player.stop()
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
        val clip = source ?: return
        player.stop()
        _ui.value = _ui.value.copy(busy = "문제를 만드는 중", result = null, playingId = null)

        val question = running.next()
        val rendered = withContext(Dispatchers.Default) {
            StimulusRenderer.render(question, clip.audio, clip.sampleRate, loudnessMatched)
        }
        player.load(rendered)

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

    /** Ranking needs the order on the buttons; everything else just needs "is it picked". */
    fun rankOf(choiceId: String): Int? = draft?.rankOf(choiceId)

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        fun factory(app: MmmApplication, family: ExerciseFamily): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ExerciseViewModel(app, family) }
            }
    }
}
