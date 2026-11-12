package mmm.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.training.ExerciseFamily
import mmm.training.ProgressStore
import mmm.training.TrialResult

/** What the home screen shows for one exercise family. */
data class FamilyProgress(
    val level: Int = 0,
    val bestLevel: Int = 0,
    val trials: Int = 0,
    val correct: Int = 0,
    val creditTotal: Double = 0.0,
) {
    val accuracy: Double get() = if (trials == 0) 0.0 else correct.toDouble() / trials
}

/**
 * [ProgressStore] backed by DataStore.
 *
 * The engine's store interface is synchronous, because a training session asks for its starting
 * level while it is being built. DataStore is not, so reads are served from an in-memory mirror
 * that tracks the file, and writes go through in the background. Callers that must not start from
 * a stale level - the exercise screen - wait on [awaitLoaded] first.
 *
 * Only running totals are kept, not every trial. They are all the screens need, and a trial log is
 * worth designing for when there is something that reads it.
 */
class DataStoreProgressStore(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : ProgressStore {

    private val _progress = MutableStateFlow<Map<ExerciseFamily, FamilyProgress>>(emptyMap())
    val progress: StateFlow<Map<ExerciseFamily, FamilyProgress>> = _progress.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            store.data.collect { prefs ->
                _progress.value = ExerciseFamily.entries.associateWith { read(prefs, it) }
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    fun of(family: ExerciseFamily): FamilyProgress = _progress.value[family] ?: FamilyProgress()

    override fun level(family: ExerciseFamily): Int = of(family).level

    override fun saveLevel(family: ExerciseFamily, level: Int) {
        scope.launch {
            store.edit { prefs ->
                prefs[levelKey(family)] = level
                prefs[bestKey(family)] = maxOf(prefs[bestKey(family)] ?: 0, level)
            }
        }
    }

    override fun record(result: TrialResult) {
        val family = result.question.family
        scope.launch {
            store.edit { prefs ->
                prefs[trialsKey(family)] = (prefs[trialsKey(family)] ?: 0) + 1
                if (result.grade.correct) {
                    prefs[correctKey(family)] = (prefs[correctKey(family)] ?: 0) + 1
                }
                prefs[creditKey(family)] = (prefs[creditKey(family)] ?: 0.0) + result.grade.credit
            }
        }
    }

    /** Starts a family over from level 0, for when the learner wants to redo the ladder. */
    fun reset(family: ExerciseFamily) {
        scope.launch {
            store.edit { prefs ->
                prefs.remove(levelKey(family))
                prefs.remove(bestKey(family))
                prefs.remove(trialsKey(family))
                prefs.remove(correctKey(family))
                prefs.remove(creditKey(family))
            }
        }
    }

    private fun read(prefs: Preferences, family: ExerciseFamily) = FamilyProgress(
        level = prefs[levelKey(family)] ?: 0,
        bestLevel = prefs[bestKey(family)] ?: 0,
        trials = prefs[trialsKey(family)] ?: 0,
        correct = prefs[correctKey(family)] ?: 0,
        creditTotal = prefs[creditKey(family)] ?: 0.0,
    )

    private fun levelKey(f: ExerciseFamily) = intPreferencesKey("progress_level_${f.id}")
    private fun bestKey(f: ExerciseFamily) = intPreferencesKey("progress_best_${f.id}")
    private fun trialsKey(f: ExerciseFamily) = intPreferencesKey("progress_trials_${f.id}")
    private fun correctKey(f: ExerciseFamily) = intPreferencesKey("progress_correct_${f.id}")
    private fun creditKey(f: ExerciseFamily) = doublePreferencesKey("progress_credit_${f.id}")
}
