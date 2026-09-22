package mmm.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the audio for the exercises comes from. */
sealed interface SourceChoice {
    val label: String

    /** Generated on the spot, so the app works before the learner has chosen anything. */
    data object PinkNoise : SourceChoice {
        override val label: String get() = "핑크 노이즈"
    }

    data class File(val uri: String, val name: String) : SourceChoice {
        override val label: String get() = name
    }
}

data class TrainerSettings(
    /**
     * On by default and only switchable for demonstration: without it a boosted band is also a
     * louder band, and the exercise can be answered on level alone.
     */
    val loudnessMatched: Boolean = true,
    val excerptSeconds: Int = DEFAULT_EXCERPT_SECONDS,
    val source: SourceChoice = SourceChoice.PinkNoise,
) {
    companion object {
        const val DEFAULT_EXCERPT_SECONDS = 8
        val EXCERPT_RANGE = 4..15
    }
}

class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<TrainerSettings> =
        store.data.map { parse(it) }.stateIn(scope, SharingStarted.Eagerly, TrainerSettings())

    /** The settings as stored, rather than the default the flow starts from before it has read. */
    suspend fun current(): TrainerSettings = store.data.map { parse(it) }.first()

    fun setLoudnessMatched(on: Boolean) = update { it[LOUDNESS_MATCHED] = on }

    fun setExcerptSeconds(seconds: Int) = update {
        it[EXCERPT_SECONDS] = seconds.coerceIn(TrainerSettings.EXCERPT_RANGE)
    }

    fun setSource(choice: SourceChoice) = update { prefs ->
        when (choice) {
            SourceChoice.PinkNoise -> {
                prefs[SOURCE_KIND] = KIND_PINK
                prefs.remove(SOURCE_URI)
                prefs.remove(SOURCE_NAME)
            }
            is SourceChoice.File -> {
                prefs[SOURCE_KIND] = KIND_FILE
                prefs[SOURCE_URI] = choice.uri
                prefs[SOURCE_NAME] = choice.name
            }
        }
    }

    // A plain function value is not accepted where DataStore wants a suspend transform, so the
    // block is called from inside a lambda literal rather than passed straight through.
    private fun update(block: (MutablePreferences) -> Unit) {
        scope.launch { store.edit { prefs -> block(prefs) } }
    }

    private fun parse(prefs: Preferences): TrainerSettings {
        val uri = prefs[SOURCE_URI]
        val source = if (prefs[SOURCE_KIND] == KIND_FILE && uri != null) {
            SourceChoice.File(uri, prefs[SOURCE_NAME] ?: "선택한 음원")
        } else {
            SourceChoice.PinkNoise
        }
        return TrainerSettings(
            loudnessMatched = prefs[LOUDNESS_MATCHED] ?: true,
            excerptSeconds = prefs[EXCERPT_SECONDS] ?: TrainerSettings.DEFAULT_EXCERPT_SECONDS,
            source = source,
        )
    }

    private companion object {
        val LOUDNESS_MATCHED = booleanPreferencesKey("settings_loudness_matched")
        val EXCERPT_SECONDS = intPreferencesKey("settings_excerpt_seconds")
        val SOURCE_KIND = stringPreferencesKey("settings_source_kind")
        val SOURCE_URI = stringPreferencesKey("settings_source_uri")
        val SOURCE_NAME = stringPreferencesKey("settings_source_name")
        const val KIND_PINK = "pink"
        const val KIND_FILE = "file"
    }
}
