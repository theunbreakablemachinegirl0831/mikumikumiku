package mmm.app.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** An album as the library shows it: tracks grouped by album and album artist. */
data class Album(val title: String, val artist: String, val tracks: List<Track>) {
    val key: String get() = "$title\u0000$artist"
}

/** A folder on the phone that has music directly in it. */
data class Folder(val path: String, val tracks: List<Track>) {
    val name: String get() = path.substringAfterLast('/').ifBlank { "내장 저장소" }
}

data class LibraryState(
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val favorites: Set<Long> get() = playlists.firstOrNull { it.name == LibraryRepository.FAVORITES }?.trackIds?.toSet().orEmpty()

    fun tracksOf(playlist: Playlist): List<Track> {
        val byId = tracks.associateBy { it.id }
        return playlist.trackIds.mapNotNull { byId[it] }
    }
}

/**
 * The phone's music and the listener's playlists, for every screen that shows them - the library
 * tabs and the now-playing screen's heart both edit the same lists.
 */
class LibraryRepository(context: Context, private val scope: CoroutineScope) {

    private val library = MusicLibrary(context)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        scope.launch {
            val stored = library.playlists()
            _state.update { it.copy(playlists = stored) }
        }
    }

    fun onPermission(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted) }
        if (granted && _state.value.tracks.isEmpty() && !_state.value.loading) refresh()
    }

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            val tracks = runCatching { library.tracks() }.getOrDefault(emptyList())
            val albums = tracks.groupBy { it.album to it.artist }
                .map { (key, list) -> Album(key.first.ifBlank { "알 수 없는 앨범" }, key.second, list) }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
            val folders = tracks.groupBy { it.folder }
                .map { (path, list) -> Folder(path, list) }
                .sortedBy { it.path.lowercase(Locale.ROOT) }
            _state.update { it.copy(loading = false, tracks = tracks, albums = albums, folders = folders) }
        }
    }

    fun createPlaylist(name: String) = editPlaylists { list ->
        if (list.any { it.name == name }) list else list + Playlist(name, emptyList())
    }

    fun deletePlaylist(name: String) = editPlaylists { list -> list.filterNot { it.name == name } }

    fun addToPlaylist(name: String, trackId: Long) = editPlaylists { list ->
        list.map { if (it.name == name && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it }
    }

    fun removeFromPlaylist(name: String, trackId: Long) = editPlaylists { list ->
        list.map { if (it.name == name) it.copy(trackIds = it.trackIds - trackId) else it }
    }

    /** The heart: favourites are a playlist like any other, made the first time it is needed. */
    fun toggleFavorite(trackId: Long) = editPlaylists { list ->
        val current = list.firstOrNull { it.name == FAVORITES }
        when {
            current == null -> listOf(Playlist(FAVORITES, listOf(trackId))) + list
            trackId in current.trackIds -> list.map { if (it.name == FAVORITES) it.copy(trackIds = it.trackIds - trackId) else it }
            else -> list.map { if (it.name == FAVORITES) it.copy(trackIds = it.trackIds + trackId) else it }
        }
    }

    private fun editPlaylists(change: (List<Playlist>) -> List<Playlist>) {
        _state.update { it.copy(playlists = change(it.playlists)) }
        val updated = _state.value.playlists
        scope.launch { library.savePlaylists(updated) }
    }

    companion object {
        const val FAVORITES = "즐겨찾기"
    }
}
