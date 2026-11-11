package mmm.app.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.player.LibraryRepository
import mmm.app.player.LibraryState
import mmm.app.player.NowPlaying
import mmm.app.player.Track
import mmm.app.ui.theme.Glass
import mmm.app.ui.theme.LocalPanelInset
import mmm.app.ui.theme.PillTabs
import mmm.app.ui.theme.ScreenHeader
import mmm.app.ui.theme.glass
import java.util.Locale

private val TABS = listOf("곡", "앨범", "폴더", "재생목록")

/**
 * The music on the phone, under the reference's pills: songs, albums, folders and playlists.
 * Each is a plain list on the backdrop; an album, folder or playlist opens in place.
 */
@Composable
fun LibraryScreen(app: MmmApplication) {
    val library by app.library.state.collectAsStateWithLifecycle()
    val now by app.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // What is open inside the current tab: an album's key, a folder's path or a playlist's name.
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), app.library::onPermission)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Playback works without it; only the notification controls are hidden.
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        app.library.onPermission(granted)
        if (!granted) askAudio.launch(audioPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    BackHandler(enabled = open != null) { open = null }

    Column(Modifier.fillMaxSize()) {
        PillTabs(
            labels = TABS,
            selected = tab,
            onSelect = {
                tab = it
                open = null
            },
            modifier = Modifier.padding(vertical = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        )
        now.error?.let { message ->
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .glass(tint = MaterialTheme.colorScheme.error)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (!now.connected) TextButton(onClick = app.player::connect) { Text("다시 연결") }
            }
        }
        if (!library.permissionGranted) {
            Column(Modifier.padding(16.dp)) {
                Text("폰에 있는 음악을 보려면 음악 파일 접근을 허용해야 한다.")
                TextButton(onClick = { askAudio.launch(audioPermission) }) { Text("허용하기") }
            }
            return@Column
        }
        AnimatedContent(
            targetState = tab to open,
            transitionSpec = {
                val (fromTab, fromOpen) = initialState
                val (toTab, toOpen) = targetState
                // Tabs slide sideways in tab order; opening an album slides deeper, closing it back.
                val direction = when {
                    toTab != fromTab -> if (toTab > fromTab) 1 else -1
                    toOpen != null && fromOpen == null -> 1
                    else -> -1
                }
                (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { direction * it / 8 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(300)) { -direction * it / 12 })
            },
            label = "library",
        ) { (current, key) ->
            when (current) {
                0 -> Songs(app, library, now)
                1 -> {
                    val album = library.albums.firstOrNull { it.key == key }
                    if (album == null) {
                        Groups(
                            count = "${library.albums.size}개 앨범",
                            entries = library.albums,
                            key = { it.key },
                            row = { a ->
                                ListRow(a.tracks.firstOrNull()?.id, a.title, "${a.artist} · ${a.tracks.size}곡", onClick = { open = a.key }) { Chevron() }
                            },
                        )
                    } else {
                        TrackList(app, library, now, album.tracks, title = album.title, subtitle = "${album.artist} · ${album.tracks.size}곡", onBack = { open = null })
                    }
                }
                2 -> {
                    val folder = library.folders.firstOrNull { it.path == key }
                    if (folder == null) {
                        Groups(
                            count = "${library.folders.size}개 폴더",
                            entries = library.folders,
                            key = { it.path },
                            row = { f ->
                                ListRow(f.tracks.firstOrNull()?.id, f.name, "${f.path.ifBlank { "/" }} · ${f.tracks.size}곡", onClick = { open = f.path }) { Chevron() }
                            },
                        )
                    } else {
                        TrackList(app, library, now, folder.tracks, title = folder.name, subtitle = "${folder.path} · ${folder.tracks.size}곡", onBack = { open = null })
                    }
                }
                else -> {
                    val playlist = library.playlists.firstOrNull { it.name == key }
                    if (playlist == null) {
                        Playlists(app, library, onOpen = { open = it })
                    } else {
                        val tracks = library.tracksOf(playlist)
                        TrackList(
                            app, library, now, tracks,
                            title = playlist.name,
                            subtitle = "${tracks.size}곡",
                            onBack = { open = null },
                            playlist = playlist.name,
                            empty = "곡 탭에서 ⋮ → 추가로 곡을 넣는다.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Songs(app: MmmApplication, library: LibraryState, now: NowPlaying) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val songs = remember(library.tracks, query) {
        val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) {
            library.tracks
        } else {
            library.tracks.filter { t ->
                val text = "${t.title} ${t.artist} ${t.album}".lowercase(Locale.ROOT)
                words.all { it in text }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalPanelInset.current)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Count(if (library.loading) "음악 목록을 읽는 중" else "${songs.size}곡", Modifier.weight(1f))
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) { Icon(Icons.Rounded.Search, contentDescription = "검색") }
            }
            AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("제목, 아티스트, 앨범") },
                    singleLine = true,
                    shape = Glass.pill,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
            TrackRow(app, library, track, playing = track.id == now.trackId, onClick = { app.player.play(songs, index) })
        }
    }
}

@Composable
private fun <T> Groups(count: String, entries: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalPanelInset.current)) {
        item { Count(count) }
        items(entries, key = key) { row(it) }
    }
}

@Composable
private fun TrackList(
    app: MmmApplication,
    library: LibraryState,
    now: NowPlaying,
    tracks: List<Track>,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    playlist: String? = null,
    empty: String? = null,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalPanelInset.current)) {
        item {
            ScreenHeader(title = title, onBack = onBack, subtitle = subtitle) {
                IconButton(
                    onClick = { app.player.play(tracks, 0) },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.glass(Glass.pill, strong = true),
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "재생") }
            }
            if (tracks.isEmpty() && empty != null) Text(empty, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(app, library, track, playing = track.id == now.trackId, onClick = { app.player.play(tracks, index) }, playlist = playlist)
        }
    }
}

@Composable
private fun Playlists(app: MmmApplication, library: LibraryState, onOpen: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val usable = name.isNotBlank() && library.playlists.none { it.name == name.trim() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalPanelInset.current)) {
        item {
            Count("${library.playlists.size}개 재생목록")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("새 재생목록 이름") },
                    singleLine = true,
                    shape = Glass.pill,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        app.library.createPlaylist(name.trim())
                        name = ""
                    },
                    enabled = usable,
                    modifier = Modifier.glass(Glass.pill, strong = true),
                ) { Icon(Icons.Rounded.Add, contentDescription = "만들기") }
            }
        }
        items(library.playlists, key = { it.name }) { playlist ->
            var menu by remember { mutableStateOf(false) }
            ListRow(
                trackId = playlist.trackIds.firstOrNull(),
                title = playlist.name,
                subtitle = "${playlist.trackIds.size}곡",
                onClick = { onOpen(playlist.name) },
            ) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "메뉴") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("재생목록 지우기") },
                            onClick = {
                                menu = false
                                app.library.deletePlaylist(playlist.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    app: MmmApplication,
    library: LibraryState,
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    playlist: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    ListRow(
        trackId = track.id,
        title = track.title,
        subtitle = "${track.artist} · ${time(track.durationMs)}",
        highlighted = playing,
        onClick = onClick,
    ) {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "메뉴") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (track.id in library.favorites) "즐겨찾기에서 빼기" else "즐겨찾기에 추가") },
                    onClick = {
                        menu = false
                        app.library.toggleFavorite(track.id)
                    },
                )
                if (playlist != null) {
                    DropdownMenuItem(
                        text = { Text("이 재생목록에서 빼기") },
                        onClick = {
                            menu = false
                            app.library.removeFromPlaylist(playlist, track.id)
                        },
                    )
                }
                library.playlists.filter { it.name != playlist && it.name != LibraryRepository.FAVORITES }.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("'${p.name}'에 추가") },
                        onClick = {
                            menu = false
                            app.library.addToPlaylist(p.name, track.id)
                        },
                    )
                }
            }
        }
    }
}

/** The reference's list row: square artwork, a title, a grey second line, something at the end. */
@Composable
private fun ListRow(
    trackId: Long?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(trackId = trackId, size = 52.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
}

@Composable
private fun Count(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}

internal fun time(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
