package mmm.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mmm.app.data.DataStoreProgressStore
import mmm.app.data.SettingsRepository
import mmm.app.live.LiveSession

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

    /** The claimed DAC and live capture, which have to outlive the screens that use them. */
    val live: LiveSession by lazy { LiveSession(applicationContext, appScope) }
}
