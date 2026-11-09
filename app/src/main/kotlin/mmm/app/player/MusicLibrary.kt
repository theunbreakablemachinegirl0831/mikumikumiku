package mmm.app.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: Uri?,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        // Carried separately as well: the session resolves from it if the URI does not survive
        // the trip from controller to session.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .build()
        )
        .build()
}

data class Playlist(val name: String, val trackIds: List<Long>)

/** The music on the phone, as Android's media index knows it, and the listener's playlists. */
class MusicLibrary(private val context: Context) {

    private val playlistFile = File(context.filesDir, "playlists.json")

    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val result = ArrayList<Track>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val trackId = cursor.getLong(id)
                result += Track(
                    id = trackId,
                    uri = ContentUris.withAppendedId(collection, trackId),
                    title = cursor.getString(title) ?: "제목 없음",
                    artist = cursor.getString(artist)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "알 수 없는 아티스트",
                    album = cursor.getString(album) ?: "",
                    durationMs = cursor.getLong(duration),
                    artworkUri = ContentUris.withAppendedId(ALBUM_ART, cursor.getLong(albumId)),
                )
            }
        }
        result
    }

    suspend fun playlists(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!playlistFile.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(playlistFile.readText())
            (0 until array.length()).map { i ->
                val p = array.getJSONObject(i)
                val ids = p.getJSONArray("tracks")
                Playlist(p.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
            }
        }.getOrDefault(emptyList())
    }

    suspend fun savePlaylists(playlists: List<Playlist>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        playlists.forEach { p ->
            array.put(JSONObject().apply {
                put("name", p.name)
                put("tracks", JSONArray().apply { p.trackIds.forEach { put(it) } })
            })
        }
        playlistFile.writeText(array.toString())
    }

    private companion object {
        val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
