package mmm.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
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
import mmm.training.FilterOverrides
import kotlin.math.asinh
import kotlin.math.ln

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
    /** Boost size for Band ID and resonance, or null to follow the difficulty ladder. */
    val filterGainDb: Double? = null,
    /** Filter Q for Band ID and resonance, or null to follow the difficulty ladder. */
    val filterQ: Double? = null,
) {
    fun filterOverrides(): FilterOverrides = FilterOverrides(gainDb = filterGainDb, q = filterQ)

    companion object {
        const val DEFAULT_EXCERPT_SECONDS = 8
        val EXCERPT_RANGE = 4..15

        /** 0.5 dB steps. The floor is audible on noise; the ceiling is where the ladder starts. */
        const val GAIN_MIN_DB = 1.0
        const val GAIN_MAX_DB = 12.0
        const val GAIN_STEP_DB = 0.5

        /** What the switch starts from when turned on: clearly audible without being harsh. */
        const val DEFAULT_GAIN_DB = 6.0

        /**
         * Q is offered as a fixed list rather than a continuous slider, because what matters is its
         * ratio - the difference between Q 1 and 2 is as large as between 16 and 32 - and a linear
         * slider would spend almost all its travel on the narrow end.
         */
        val Q_VALUES = listOf(0.7, 1.0, 1.4, 2.0, 2.9, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 40.0)
        const val DEFAULT_Q = 4.0

        /** Bandwidth in octaves between the half-gain points of a peaking filter with this Q. */
        fun octavesForQ(q: Double): Double = 2.0 / ln(2.0) * asinh(1.0 / (2.0 * q))
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

    /** @param db null to hand the gain back to the ladder */
    fun setFilterGain(db: Double?) = update { prefs ->
        if (db == null) {
            prefs.remove(FILTER_GAIN_DB)
        } else {
            prefs[FILTER_GAIN_DB] = db.coerceIn(TrainerSettings.GAIN_MIN_DB, TrainerSettings.GAIN_MAX_DB)
        }
    }

    /** @param q null to hand the Q back to the ladder */
    fun setFilterQ(q: Double?) = update { prefs ->
        if (q == null) {
            prefs.remove(FILTER_Q)
        } else {
            prefs[FILTER_Q] = q
        }
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
            filterGainDb = prefs[FILTER_GAIN_DB],
            filterQ = prefs[FILTER_Q],
        )
    }

    private companion object {
        val LOUDNESS_MATCHED = booleanPreferencesKey("settings_loudness_matched")
        val EXCERPT_SECONDS = intPreferencesKey("settings_excerpt_seconds")
        val SOURCE_KIND = stringPreferencesKey("settings_source_kind")
        val SOURCE_URI = stringPreferencesKey("settings_source_uri")
        val SOURCE_NAME = stringPreferencesKey("settings_source_name")
        val FILTER_GAIN_DB = doublePreferencesKey("settings_filter_gain_db")
        val FILTER_Q = doublePreferencesKey("settings_filter_q")
        const val KIND_PINK = "pink"
        const val KIND_FILE = "file"
    }
}
