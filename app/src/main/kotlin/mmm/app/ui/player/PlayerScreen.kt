package mmm.app.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import mmm.app.MmmApplication
import mmm.app.player.NowPlaying
import mmm.app.player.Track
import mmm.app.ui.theme.Card
import mmm.app.ui.theme.FilterChip
import mmm.app.ui.theme.Glass
import mmm.app.ui.theme.GroupCard
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.ScreenHeader
import mmm.app.ui.theme.SettingRow
import mmm.app.ui.theme.Switch
import mmm.app.ui.theme.glass

private val TABS = listOf("재생 중", "모든 노래", "앨범", "재생목록", "Spotify")

@Composable
fun PlayerScreen(app: MmmApplication, onOpenEarphones: () -> Unit) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by app.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onPermission)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Playback works without it; only the notification controls are hidden.
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        vm.onPermission(granted)
        if (!granted) askAudio.launch(audioPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TABS.forEachIndexed { index, label ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            }
        }
        now.error?.let { message ->
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (!now.connected) TextButton(onClick = app.player::connect) { Text("다시 연결") }
                }
            }
        }
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (fadeIn(tween(240)) + slideInHorizontally(tween(280)) { direction * it / 8 }) togetherWith fadeOut(tween(160))
            },
            label = "player-tab",
        ) { current ->
            when (current) {
                0 -> NowPlayingView(app, now, onOpenEarphones)
                1 -> Songs(ui, vm, now, onAsk = { askAudio.launch(audioPermission) })
                2 -> Albums(ui, vm, now)
                3 -> Playlists(ui, vm, now)
                else -> SpotifyTab(app)
            }
        }
    }
}

@Composable
private fun NowPlayingView(app: MmmApplication, now: NowPlaying, onOpenEarphones: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val correction by app.activeCorrection.collectAsStateWithLifecycle()
    val report by app.live.usb.report.collectAsStateWithLifecycle()
    val earphone = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    // The artwork breathes with the music: full size while playing, slightly sunk when paused.
    val artScale by animateFloatAsState(if (now.isPlaying) 1f else 0.9f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "art")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Crossfade(targetState = now.trackId, animationSpec = tween(400), label = "artwork") { id ->
                Box(Modifier.fillMaxSize().scale(artScale), contentAlignment = Alignment.Center) {
                    Artwork(trackId = id, size = 1000.dp, corner = 28.dp, bitmap = now.artwork, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Column {
            Text(
                now.title ?: "재생 중인 곡 없음",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(now.artist, now.album).joinToString(" · ").ifBlank { "모든 노래 탭에서 곡을 고른다" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SeekBar(app, now)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = app.player::previous, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "이전 곡", modifier = Modifier.size(44.dp))
            }
            IconButton(onClick = app.player::toggle, modifier = Modifier.size(84.dp).glass(Glass.pill, strong = true)) {
                Crossfade(now.isPlaying, label = "play") { playing ->
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "일시정지" else "재생",
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            IconButton(onClick = app.player::next, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "다음 곡", modifier = Modifier.size(44.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = app.player::cycleRepeat) {
                Icon(
                    if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "반복",
                    tint = if (now.repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.45f) else Color.White,
                )
            }
            IconButton(onClick = app.player::toggleShuffle) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "셔플",
                    tint = if (now.shuffle) Color.White else Color.White.copy(alpha = 0.45f),
                )
            }
            IconButton(onClick = onOpenEarphones) {
                Icon(Icons.Rounded.Tune, contentDescription = "이어폰 EQ")
            }
        }

        // The status pill under the controls, as the reference shows the output format there.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.glass(Glass.pill).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        earphone == null -> "이어폰 EQ 없음"
                        correction == null -> "${earphone.name} · EQ 꺼짐"
                        else -> "${earphone.name} · EQ 켜짐"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Switch(checked = settings.eqEnabled, onCheckedChange = app.settings::setEqEnabled, modifier = Modifier.scale(0.8f))
            }
        }

        if (report.claim?.claimed == true) {
            Text(
                "실시간 모드가 USB DAC을 점유하고 있어 소리가 폰 스피커로 나간다. 실시간 화면에서 점유를 해제한다.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                SpectrumView(playing = now.isPlaying, eq = correction, modifier = Modifier.fillMaxWidth())
            }
        }

        if (now.queue.isNotEmpty()) {
            Text("다음 곡", style = MaterialTheme.typography.titleMedium)
            GroupCard {
                now.queue.forEachIndexed { index, title ->
                    if (index <= now.queueIndex || index > now.queueIndex + QUEUE_AHEAD) return@forEachIndexed
                    Text(
                        title,
                        modifier = Modifier.fillMaxWidth().clickable { app.player.skipTo(index) }.padding(horizontal = 18.dp, vertical = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SeekBar(app: MmmApplication, now: NowPlaying) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = now.durationMs.coerceAtLeast(1)
    Column {
        Slider(
            value = if (dragging) dragValue else (now.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { app.player.seekTo((dragValue * duration).toLong()); dragging = false },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(if (dragging) (dragValue * duration).toLong() else now.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(time(now.durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Songs(ui: LibraryUiState, vm: PlayerViewModel, now: NowPlaying, onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!ui.permissionGranted) {
            Text("폰에 있는 음악을 보려면 음악 파일 접근을 허용해야 한다.", modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onAsk) { Text("허용하기") }
            return@Column
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::search,
            placeholder = { Text("제목, 아티스트, 앨범 검색") },
            singleLine = true,
            shape = Glass.pill,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        val songs = ui.filtered
        Text(
            if (ui.loading) "음악 목록을 읽는 중" else "${songs.size}곡",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, ui, vm, playing = track.id == now.trackId, onClick = { vm.play(songs, index) })
            }
        }
    }
}

@Composable
private fun Albums(ui: LibraryUiState, vm: PlayerViewModel, now: NowPlaying) {
    val open = ui.albums.firstOrNull { it.key == ui.openAlbum }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        AnimatedContent(targetState = open, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "album") { album ->
            if (album == null) {
                Column {
                    Text(
                        "${ui.albums.size}개 앨범",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(ui.albums, key = { it.key }) { a ->
                            ListRow(
                                trackId = a.tracks.firstOrNull()?.id,
                                title = a.title,
                                subtitle = "${a.artist} · ${a.tracks.size}곡",
                                onClick = { vm.openAlbum(a.key) },
                                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                            )
                        }
                    }
                }
            } else {
                Column {
                    ScreenHeader(title = album.title, onBack = { vm.openAlbum(null) }, subtitle = "${album.artist} · ${album.tracks.size}곡")
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(album.tracks, key = { _, t -> t.id }) { index, track ->
                            TrackRow(track, ui, vm, playing = track.id == now.trackId, onClick = { vm.play(album.tracks, index) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Playlists(ui: LibraryUiState, vm: PlayerViewModel, now: NowPlaying) {
    val open = ui.playlists.firstOrNull { it.name == ui.openPlaylist }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open != null) {
            val tracks = vm.tracksOf(open)
            ScreenHeader(title = open.name, onBack = { vm.openPlaylist(null) }, subtitle = "${tracks.size}곡") {
                IconButton(onClick = { vm.play(tracks, 0) }, enabled = tracks.isNotEmpty(), modifier = Modifier.glass(Glass.pill, strong = true)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "재생")
                }
            }
            if (tracks.isEmpty()) Text("모든 노래에서 ⋮ → 추가로 곡을 넣는다.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    TrackRow(track, ui, vm, playing = track.id == now.trackId, onClick = { vm.play(tracks, index) }, playlist = open.name)
                }
            }
            return@Column
        }

        var name by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("새 재생목록 이름") },
                singleLine = true,
                shape = Glass.pill,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = name.isNotBlank() && ui.playlists.none { it.name == name.trim() },
                onClick = {
                    if (name.isNotBlank() && ui.playlists.none { it.name == name.trim() }) {
                        vm.createPlaylist(name.trim())
                        name = ""
                    }
                },
                label = { Text("만들기") },
            )
        }
        GroupCard {
            if (ui.playlists.isEmpty()) {
                Text("재생목록이 없다.", modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall)
            }
            ui.playlists.forEachIndexed { index, playlist ->
                if (index > 0) GroupDivider()
                SettingRow(
                    title = playlist.name,
                    subtitle = "${playlist.trackIds.size}곡",
                    onClick = { vm.openPlaylist(playlist.name) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    ui: LibraryUiState,
    vm: PlayerViewModel,
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
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "메뉴") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (playlist != null) {
                        DropdownMenuItem(
                            text = { Text("재생목록에서 빼기") },
                            onClick = { vm.removeFromPlaylist(playlist, track); menu = false },
                        )
                    }
                    ui.playlists.filter { it.name != playlist }.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("'${p.name}'에 추가") },
                            onClick = { vm.addToPlaylist(p.name, track); menu = false },
                        )
                    }
                    if (ui.playlists.isEmpty()) {
                        DropdownMenuItem(text = { Text("재생목록 탭에서 먼저 재생목록을 만든다") }, onClick = { menu = false })
                    }
                }
            }
        },
    )
}

/** The reference's list row: square artwork, title, a grey second line, something at the end. */
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
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
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

private const val QUEUE_AHEAD = 8

internal fun time(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
