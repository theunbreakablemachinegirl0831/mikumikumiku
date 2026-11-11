package mmm.app.spotify

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.player.PlayerConnection

/**
 * Exists only so the system will tell this app which media sessions are active: that list is
 * given to notification listeners and nobody else. It reads no notifications.
 */
class MediaAccessService : NotificationListenerService()

/** What another app is playing, as its media session describes it. */
data class ExternalNowPlaying(
    /** Whether the listener has let this app see other apps' media sessions. */
    val access: Boolean = false,
    val packageName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ImageBitmap? = null,
    val tint: Color? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    /** Position when it was last reported, and when that was, on the elapsed-realtime clock. */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    val speed: Float = 1f,
) {
    val hasSession: Boolean get() = packageName != null

    /** Where playback is now: sessions report their position only when it jumps. */
    fun positionNow(): Long {
        if (!isPlaying) return positionMs
        val now = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
        return if (durationMs > 0) now.coerceIn(0L, durationMs) else now.coerceAtLeast(0L)
    }
}

/**
 * Follows another music app's media session - Spotify's first, if it has one - so the Spotify
 * tab can show what is playing and drive it. Nothing is played or captured here; the audio is
 * reached separately, by the session EQ.
 */
class ExternalPlayback(private val context: Context, private val scope: CoroutineScope) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var listening = false
    private var artworkOf: Bitmap? = null

    private val _state = MutableStateFlow(ExternalNowPlaying())
    val state: StateFlow<ExternalNowPlaying> = _state.asStateFlow()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> choose(controllers.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Looks again at what is playing; called when the Spotify tab comes on screen. */
    fun refresh() {
        handler.post {
            if (!hasAccess()) {
                follow(null)
                _state.value = ExternalNowPlaying(access = false)
                return@post
            }
            runCatching {
                if (!listening) {
                    manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
                    listening = true
                }
                choose(manager.getActiveSessions(component))
            }.onFailure {
                // Access was withdrawn between the check and the call.
                follow(null)
                _state.value = ExternalNowPlaying(access = false)
            }
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (_state.value.isPlaying) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms)
    }

    private fun choose(controllers: List<MediaController>) {
        val others = controllers.filter { it.packageName != context.packageName }
        follow(others.firstOrNull { it.packageName == SPOTIFY } ?: others.firstOrNull())
        publish()
    }

    private fun follow(next: MediaController?) {
        val current = controller
        if (current?.sessionToken == next?.sessionToken && current != null) return
        runCatching { current?.unregisterCallback(callback) }
        controller = next
        runCatching { next?.registerCallback(callback, handler) }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            artworkOf = null
            _state.value = ExternalNowPlaying(access = hasAccess())
            return
        }
        val metadata = c.metadata
        val playback = c.playbackState
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        _state.value = _state.value.copy(
            access = true,
            packageName = c.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            positionMs = playback?.position?.coerceAtLeast(0) ?: 0,
            positionAt = playback?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed ?: 1f,
        )
        if (bitmap !== artworkOf) {
            artworkOf = bitmap
            if (bitmap == null) {
                _state.value = _state.value.copy(artwork = null, tint = null)
            } else {
                scope.launch(Dispatchers.Default) {
                    // Copied, because the session may recycle its bitmap when the track changes.
                    val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return@launch
                    val tint = PlayerConnection.dominantColor(copy)
                    if (artworkOf === bitmap) _state.value = _state.value.copy(artwork = copy.asImageBitmap(), tint = tint)
                }
            }
        }
    }

    companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
