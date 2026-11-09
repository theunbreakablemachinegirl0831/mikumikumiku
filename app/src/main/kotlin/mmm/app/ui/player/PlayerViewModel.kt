package mmm.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.player.MusicLibrary
import mmm.app.player.Playlist
import mmm.app.player.Track
import java.util.Locale

/** An album as the library shows it: tracks grouped by album and album artist. */
data class Album(val title: String, val artist: String, val tracks: List<Track>) {
    val key: String get() = "$title\u0000$artist"
}

data class LibraryUiState(
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val query: String = "",
    val playlists: List<Playlist> = emptyList(),
    val openPlaylist: String? = null,
    val openAlbum: String? = null,
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
 * The library half of the player: the phone's music and the playlists. Playback itself is the
 * app-wide [mmm.app.player.PlayerConnection], so the mini player and this screen see one state.
 */
class PlayerViewModel(private val app: MmmApplication) : ViewModel() {

    private val library = MusicLibrary(app)

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    init {
        app.player.connect()
        viewModelScope.launch { _ui.value = _ui.value.copy(playlists = library.playlists()) }
    }

    fun onPermission(granted: Boolean) {
        _ui.value = _ui.value.copy(permissionGranted = granted)
        if (granted && _ui.value.tracks.isEmpty()) {
            viewModelScope.launch {
                _ui.value = _ui.value.copy(loading = true)
                val tracks = runCatching { library.tracks() }.getOrDefault(emptyList())
                val albums = tracks.groupBy { it.album to it.artist }
                    .map { (key, list) -> Album(key.first.ifBlank { "알 수 없는 앨범" }, key.second, list) }
                    .sortedBy { it.title.lowercase(Locale.ROOT) }
                _ui.value = _ui.value.copy(loading = false, tracks = tracks, albums = albums)
            }
        }
    }

    fun search(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun play(list: List<Track>, start: Int) = app.player.play(list, start)

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

    fun openAlbum(key: String?) {
        _ui.value = _ui.value.copy(openAlbum = key)
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

    companion object {
        fun factory(app: MmmApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(app) }
        }
    }
}
