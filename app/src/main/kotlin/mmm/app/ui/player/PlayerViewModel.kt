package mmm.app.ui.player

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.player.MusicLibrary
import mmm.app.player.PlaybackService
import mmm.app.player.Playlist
import mmm.app.player.Track
import java.util.Locale

data class PlayerUiState(
    val connected: Boolean = false,
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val query: String = "",
    val playlists: List<Playlist> = emptyList(),
    val openPlaylist: String? = null,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<String> = emptyList(),
    val queueIndex: Int = -1,
) {
    val filtered: List<Track>
        get() {
            val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return tracks
            return tracks.filter { t ->
                val text = "${t.title} ${t.artist} ${t.album}".lowercase(Locale.ROOT)
                words.all { it in text }
            }
        }
}

/**
 * The player screen's side of the music: a MediaController talking to [PlaybackService], the
 * phone's music library and the playlists. The service owns playback, so the music outlives this
 * screen; the controller is only how the screen sees and steers it.
 */
class PlayerViewModel(private val app: MmmApplication) : ViewModel() {

    private val library = MusicLibrary(app)
    private var controller: MediaController? = null

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val built = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = built
            built.addListener(listener)
            publish(built)
            _ui.value = _ui.value.copy(connected = true)
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            // Position is not an event; it is polled while the screen is up.
            while (isActive) {
                controller?.let { c ->
                    _ui.value = _ui.value.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0))
                }
                delay(500)
            }
        }
        viewModelScope.launch { _ui.value = _ui.value.copy(playlists = library.playlists()) }
    }

    fun onPermission(granted: Boolean) {
        _ui.value = _ui.value.copy(permissionGranted = granted)
        if (granted && _ui.value.tracks.isEmpty()) {
            viewModelScope.launch {
                _ui.value = _ui.value.copy(loading = true)
                val tracks = runCatching { library.tracks() }.getOrDefault(emptyList())
                _ui.value = _ui.value.copy(loading = false, tracks = tracks)
            }
        }
    }

    fun search(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    /** Plays [list] as the queue, starting from [start]. */
    fun play(list: List<Track>, start: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map(Track::toMediaItem), start.coerceIn(list.indices), 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() = controller?.seekToPrevious()

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun skipTo(index: Int) = controller?.seekTo(index, 0L)

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

    fun createPlaylist(name: String) = editPlaylists { it + Playlist(name, emptyList()) }

    fun deletePlaylist(name: String) = editPlaylists { list -> list.filterNot { it.name == name } }

    fun addToPlaylist(name: String, track: Track) = editPlaylists { list ->
        list.map { if (it.name == name && track.id !in it.trackIds) it.copy(trackIds = it.trackIds + track.id) else it }
    }

    fun removeFromPlaylist(name: String, track: Track) = editPlaylists { list ->
        list.map { if (it.name == name) it.copy(trackIds = it.trackIds - track.id) else it }
    }

    fun openPlaylist(name: String?) {
        _ui.value = _ui.value.copy(openPlaylist = name)
    }

    fun tracksOf(playlist: Playlist): List<Track> {
        val byId = _ui.value.tracks.associateBy { it.id }
        return playlist.trackIds.mapNotNull { byId[it] }
    }

    private fun editPlaylists(change: (List<Playlist>) -> List<Playlist>) {
        val updated = change(_ui.value.playlists)
        _ui.value = _ui.value.copy(playlists = updated)
        viewModelScope.launch { library.savePlaylists(updated) }
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val queue = (0 until player.mediaItemCount).map { index ->
            val item: MediaItem = player.getMediaItemAt(index)
            item.mediaMetadata.title?.toString() ?: "제목 없음"
        }
        _ui.value = _ui.value.copy(
            isPlaying = player.isPlaying,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = queue,
            queueIndex = player.currentMediaItemIndex,
            durationMs = player.duration.coerceAtLeast(0),
        )
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }

    companion object {
        fun factory(app: MmmApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(app) }
        }
    }
}
