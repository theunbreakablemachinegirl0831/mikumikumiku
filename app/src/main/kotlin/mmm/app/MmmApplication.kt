package mmm.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import mmm.app.audiometry.AudiogramStore
import mmm.app.data.DataStoreProgressStore
import mmm.app.data.EarphoneRepository
import mmm.app.data.EqRepository
import mmm.app.data.SettingsRepository
import mmm.app.live.LiveSession
import mmm.app.player.LibraryRepository
import mmm.app.player.PlayerConnection
import mmm.app.sessioneq.SessionEqService
import mmm.app.spotify.ExternalPlayback
import mmm.eq.EqProfile

private val Context.trainerDataStore: DataStore<Preferences> by preferencesDataStore(name = "trainer")

/**
 * Holds the app's few long-lived objects.
 *
 * A handful of stores, the player connection and the live session are all that outlive a screen,
 * which is too little to justify a dependency-injection framework; screens reach them through here.
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

    /** The phone's music and the playlists, shared by the library and the now-playing screen. */
    val library: LibraryRepository by lazy { LibraryRepository(this, appScope) }

    /** The playback EQ: settings, the automatic fit, and the profile that results. */
    val eq: EqRepository by lazy { EqRepository(applicationContext, appScope, settings, earphones, audiograms) }

    /** What another app (Spotify) is playing, when the listener has let the app see it. */
    val external: ExternalPlayback by lazy { ExternalPlayback(this, appScope) }

    /** The EQ every playback path applies; null means play untouched. */
    val activeCorrection: StateFlow<EqProfile?> get() = eq.profile

    /** The claimed DAC and live capture, which have to outlive the screens that use them. */
    val live: LiveSession by lazy { LiveSession(applicationContext, appScope, activeCorrection) }

    /**
     * Starts or stops the service that puts the EQ on other apps, to match the setting. Called
     * while the app is on screen - a foreground service may not be started from the background.
     */
    fun syncSystemEq() {
        runCatching {
            if (eq.state.value.systemEq) SessionEqService.start(this) else SessionEqService.stop(this)
        }
    }
}
