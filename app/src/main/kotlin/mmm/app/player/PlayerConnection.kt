package mmm.app.player

import android.content.ComponentName
import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mmm.app.MmmApplication

data class NowPlaying(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val trackId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<String> = emptyList(),
    val queueIndex: Int = -1,
    val artwork: ImageBitmap? = null,
    /** The artwork's colour, for the backdrop; null without artwork. */
    val tint: Color? = null,
    val error: String? = null,
)

/**
 * The app's single connection to [PlaybackService]: every screen - the player, the mini player in
 * the bottom panel, the backdrop that takes its colour from the album art - reads the same state.
 */
class PlayerConnection(private val app: MmmApplication, private val scope: CoroutineScope) {

    private var controller: MediaController? = null
    private var connecting = false
    private var artworkFor: Long? = null

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "재생 오류: ${error.errorCodeName} - ${error.message ?: ""}")
        }
    }

    init {
        scope.launch(Dispatchers.Main) {
            // Position is not an event; it is polled.
            while (true) {
                controller?.let { c ->
                    val position = c.currentPosition.coerceAtLeast(0)
                    if (position != _state.value.positionMs) {
                        _state.value = _state.value.copy(positionMs = position, durationMs = c.duration.coerceAtLeast(0))
                    }
                }
                delay(500)
            }
        }
    }

    /** Connects to the playback service; a failure is shown rather than swallowed. */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }
                .onSuccess { built ->
                    controller = built
                    built.addListener(listener)
                    _state.value = _state.value.copy(connected = true, error = null)
                    publish(built)
                }
                .onFailure { e ->
                    val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                    _state.value = _state.value.copy(
                        connected = false,
                        error = "재생 서비스에 연결하지 못했다: ${cause.message ?: cause::class.simpleName}",
                    )
                }
        }, ContextCompat.getMainExecutor(app))
    }

    fun play(list: List<Track>, start: Int) {
        if (list.isEmpty()) return
        val c = controller
        if (c == null) {
            _state.value = _state.value.copy(error = "재생 서비스에 아직 연결되지 않았다. 다시 연결하는 중이다.")
            connect()
            return
        }
        _state.value = _state.value.copy(error = null)
        c.setMediaItems(list.map(Track::toMediaItem), start.coerceIn(list.indices), 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun skipTo(index: Int) {
        controller?.seekTo(index, 0L)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            hasMedia = player.mediaItemCount > 0,
            trackId = trackId,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "제목 없음" },
            queueIndex = player.currentMediaItemIndex,
            durationMs = player.duration.coerceAtLeast(0),
        )
        if (trackId != artworkFor) loadArtwork(trackId)
    }

    private fun loadArtwork(trackId: Long?) {
        artworkFor = trackId
        if (trackId == null) {
            _state.value = _state.value.copy(artwork = null, tint = null)
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { artwork(app, trackId, ART_SIZE) }
            if (artworkFor != trackId) return@launch
            _state.value = _state.value.copy(artwork = bitmap?.asImageBitmap(), tint = bitmap?.let(::dominantColor))
        }
    }

    companion object {
        private const val ART_SIZE = 720

        /** The embedded or folder artwork Android has for a track, or null. */
        fun artwork(app: MmmApplication, trackId: Long, size: Int): Bitmap? = runCatching {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
            app.contentResolver.loadThumbnail(uri, Size(size, size), null)
        }.getOrNull()

        /**
         * The colour to wash the backdrop with: the average of the more colourful pixels, so a
         * grey sky with one blue streak still gives blue rather than grey.
         */
        fun dominantColor(bitmap: Bitmap): Color {
            val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
            var r = 0f
            var g = 0f
            var b = 0f
            var weight = 0f
            val hsv = FloatArray(3)
            for (x in 0 until small.width) {
                for (y in 0 until small.height) {
                    val pixel = small.getPixel(x, y)
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val w = 0.15f + hsv[1] * hsv[2]
                    r += android.graphics.Color.red(pixel) * w
                    g += android.graphics.Color.green(pixel) * w
                    b += android.graphics.Color.blue(pixel) * w
                    weight += w
                }
            }
            val mixed = android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
            android.graphics.Color.colorToHSV(mixed, hsv)
            // Keep it a wash: some colour, never neon, never black.
            hsv[1] = hsv[1].coerceIn(0.25f, 0.6f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.7f)
            return Color(android.graphics.Color.HSVToColor(hsv))
        }
    }
}
