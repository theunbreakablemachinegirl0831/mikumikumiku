package mmm.app.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import mmm.app.MmmApplication
import mmm.app.player.Track
import mmm.app.ui.theme.ScreenHeader

@Composable
fun PlayerScreen(app: MmmApplication, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
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

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ScreenHeader(title = "플레이어", onBack = onBack) }
            TabRow(selectedTabIndex = tab) {
                listOf("재생 중", "곡", "재생목록").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> NowPlaying(app, ui, vm)
                1 -> Songs(ui, vm, onAsk = { askAudio.launch(audioPermission) })
                else -> Playlists(ui, vm)
            }
        }
    }
}

@Composable
private fun NowPlaying(app: MmmApplication, ui: PlayerUiState, vm: PlayerViewModel) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val correction by app.activeCorrection.collectAsStateWithLifecycle()
    val report by app.live.usb.report.collectAsStateWithLifecycle()
    val earphone = earphones.firstOrNull { it.id == settings.activeEarphoneId }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ui.title ?: "재생 중인 곡 없음", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ui.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

        SpectrumView(playing = ui.isPlaying, eq = correction, modifier = Modifier.fillMaxWidth())

        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val duration = ui.durationMs.coerceAtLeast(1)
        Slider(
            value = if (dragging) dragValue else (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { vm.seekTo((dragValue * duration).toLong()); dragging = false },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(ui.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(time(ui.durationMs), style = MaterialTheme.typography.labelMedium)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::toggleShuffle) { Text(if (ui.shuffle) "셔플 켬" else "셔플") }
            TextButton(onClick = { vm.previous() }) { Text("⏮", fontSize = 28.sp) }
            FilledIconButton(onClick = vm::toggle, modifier = Modifier.padding(4.dp)) {
                Text(if (ui.isPlaying) "⏸" else "▶", fontSize = 22.sp)
            }
            TextButton(onClick = { vm.next() }) { Text("⏭", fontSize = 28.sp) }
            TextButton(onClick = vm::cycleRepeat) {
                Text(
                    when (ui.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "전체 반복"
                        Player.REPEAT_MODE_ONE -> "한 곡 반복"
                        else -> "반복"
                    }
                )
            }
        }

        Card {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("이어폰 EQ", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            earphone == null -> "고른 이어폰 없음 (설정 → 이어폰 보정)"
                            correction == null && settings.eqEnabled -> "${earphone.name} · 아직 EQ 없음"
                            else -> earphone.name
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.eqEnabled, onCheckedChange = app.settings::setEqEnabled)
            }
        }
        if (report.claim?.claimed == true) {
            Text(
                "실시간 모드가 USB DAC을 점유하고 있어 플레이어 소리는 폰 스피커로 나간다. 실시간 화면에서 점유를 해제한다.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (ui.queue.isNotEmpty()) {
            Text("대기열", style = MaterialTheme.typography.titleSmall)
            ui.queue.forEachIndexed { index, title ->
                if (index < ui.queueIndex - 1 || index > ui.queueIndex + QUEUE_AHEAD) return@forEachIndexed
                Text(
                    (if (index == ui.queueIndex) "▶ " else "   ") + title,
                    fontWeight = if (index == ui.queueIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clickable { vm.skipTo(index) }.padding(vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Songs(ui: PlayerUiState, vm: PlayerViewModel, onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!ui.permissionGranted) {
            Text("폰에 있는 음악을 보려면 음악 파일 접근을 허용해야 한다.", modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onAsk) { Text("허용하기") }
            return@Column
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::search,
            label = { Text("제목, 아티스트, 앨범 검색") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        val songs = ui.filtered
        Text(
            if (ui.loading) "음악 목록을 읽는 중" else "${songs.size}곡",
            style = MaterialTheme.typography.labelMedium,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs) { index, track ->
                TrackRow(track, ui, vm, onClick = { vm.play(songs, index) })
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, ui: PlayerUiState, vm: PlayerViewModel, onClick: () -> Unit, playlist: String? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${track.artist} · ${time(track.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
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
    }
    HorizontalDivider()
}

@Composable
private fun Playlists(ui: PlayerUiState, vm: PlayerViewModel) {
    val open = ui.playlists.firstOrNull { it.name == ui.openPlaylist }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open != null) {
            val tracks = vm.tracksOf(open)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.openPlaylist(null) }) { Text("← 목록") }
                Text(open.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { vm.play(tracks, 0) }, enabled = tracks.isNotEmpty()) { Text("재생") }
            }
            if (tracks.isEmpty()) Text("곡 탭에서 ⋮ → 추가로 곡을 넣는다.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(track, ui, vm, onClick = { vm.play(tracks, index) }, playlist = open.name)
                }
            }
            return@Column
        }

        var name by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("새 재생목록 이름") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { vm.createPlaylist(name.trim()); name = "" },
                enabled = name.isNotBlank() && ui.playlists.none { it.name == name.trim() },
            ) { Text("만들기") }
        }
        ui.playlists.forEach { playlist ->
            Card(Modifier.fillMaxWidth().clickable { vm.openPlaylist(playlist.name) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text("${playlist.trackIds.size}곡", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.deletePlaylist(playlist.name) }) { Text("삭제") }
                }
            }
        }
    }
}

private const val QUEUE_AHEAD = 8

private fun time(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
