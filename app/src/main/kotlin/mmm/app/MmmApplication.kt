package mmm.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import mmm.app.audiometry.AudiogramStore
import mmm.app.data.DataStoreProgressStore
import mmm.app.data.EarphoneRepository
import mmm.eq.EqProfile
import mmm.app.data.SettingsRepository
import mmm.app.live.LiveSession
import mmm.app.player.PlayerConnection

private val Context.trainerDataStore: DataStore<Preferences> by preferencesDataStore(name = "trainer")

/**
 * Holds the app's few long-lived objects.
 *
 * Two stores and the live session are all that outlive a screen, which is too little to justify a
 * dependency-injection framework; screens reach them through here.
 */
class MmmApplication : Application() {

    /** Outlives every screen, so progress writes are not lost when the learner backs out mid-save. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val progress: DataStoreProgressStore by lazy {
        DataStoreProgressStore(applicationContext.trainerDataStore, appScope)
    }

    val settings: SettingsRepository by lazy {
        SettingsRepository(applicationContext.trainerDataStore, appScope)
    }

    val earphones: EarphoneRepository by lazy { EarphoneRepository(applicationContext, appScope) }

    val audiograms: AudiogramStore by lazy { AudiogramStore(applicationContext) }

    /** The one connection to the music player, shared by the player screen and the mini player. */
    val player: PlayerConnection by lazy { PlayerConnection(this, appScope).also { it.connect() } }

    /**
     * The EQ every playback path applies: the active earphone's, when EQ is on and one has been
     * fitted. Null means play untouched.
     */
    val activeCorrection: StateFlow<EqProfile?> by lazy {
        combine(settings.settings, earphones.earphones) { s, list ->
            if (!s.eqEnabled) null else list.firstOrNull { it.id == s.activeEarphoneId }?.eq
        }.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** The claimed DAC and live capture, which have to outlive the screens that use them. */
    val live: LiveSession by lazy { LiveSession(applicationContext, appScope, activeCorrection) }
}
