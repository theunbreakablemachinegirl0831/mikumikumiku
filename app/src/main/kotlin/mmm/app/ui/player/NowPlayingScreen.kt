package mmm.app.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import mmm.app.ui.components.glass.GlassMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import mmm.app.MmmApplication
import mmm.app.player.LibraryRepository
import mmm.app.player.NowPlaying
import mmm.app.player.PlaybackFormat
import mmm.app.player.PlaybackService
import mmm.app.ui.components.glass.GlassBottomSheet
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.glass
import mmm.app.data.TrainerSettings
import mmm.app.sessioneq.SessionEqService
import mmm.app.spotify.ExternalNowPlaying
import mmm.app.ui.spotify.appName
import mmm.app.ui.visual.PlayerSpectrumSource
import mmm.app.ui.visual.SpectrumLayers
import mmm.app.ui.visual.SpectrumOptions
import mmm.app.ui.visual.SpectrumSource
import mmm.app.ui.visual.VisualizerSheet
import mmm.app.ui.visual.VisualizerSpectrumSource
import mmm.app.ui.visual.drawSpectrumLayers
import mmm.app.ui.visual.rememberSpectrum
import mmm.app.ui.visual.visualizerPaint
import kotlin.math.roundToInt

/**
 * The now-playing screen, laid out as the reference: the artwork as large as the screen allows
 * with the spectrum moving across its foot, the title and a heart, the position, the transport,
 * the volume, a row of four small controls and the format line - all on one screen, no scrolling.
 *
 * With [external] it shows another app's playback (Spotify) the same way, so the mini player
 * opens into the same screen whichever it is showing. What the other app does not let this one
 * do - favourites, repeat, shuffle, its queue - is left out rather than shown disabled.
 *
 * @param artAlpha the artwork's opacity: while the screen is opening or closing the artwork is
 *   drawn by the shell, flying between here and the mini player, and this one stays hidden
 * @param onArtBounds where the artwork sits, so the shell knows where to fly it to
 */
@Composable
fun NowPlayingScreen(
    app: MmmApplication,
    now: NowPlaying,
    artAlpha: () -> Float,
    onArtBounds: (Rect) -> Unit,
    onOpenEq: () -> Unit,
    modifier: Modifier = Modifier,
    external: ExternalNowPlaying? = null,
) {
    val library by app.library.state.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val eq by app.eq.state.collectAsStateWithLifecycle()
    val profile by app.eq.profile.collectAsStateWithLifecycle()
    val format by PlaybackService.format.collectAsStateWithLifecycle()
    val report by app.live.usb.report.collectAsStateWithLifecycle()
    val sessions by SessionEqService.status.collectAsStateWithLifecycle()
    val colors = GlassTheme.colors
    var menu by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    var visualizer by remember { mutableStateOf(false) }
    val favorite = external == null && now.trackId != null && now.trackId in library.favorites

    val playing = external?.isPlaying ?: now.isPlaying
    val title = if (external != null) external.title ?: "제목 없음" else now.title ?: "재생 중인 곡 없음"
    val subtitle = if (external != null) {
        listOfNotNull(external.artist, external.album?.takeIf { it.isNotBlank() }, appName(external.packageName)).joinToString(" · ")
    } else {
        listOfNotNull(now.artist, now.album?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "곡을 고르면 여기에 나온다" }
    }
    val duration = external?.durationMs ?: now.durationMs
    val position = if (external != null) {
        rememberPlaybackPosition(running = external.isPlaying, anchor = external.positionMs to external.positionAt) { external.positionNow() }
    } else {
        rememberPlaybackPosition(running = now.isPlaying, anchor = now.positionMs to now.trackId) { app.player.positionNow() }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The artwork takes whatever height the controls leave, and stays square.
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            ArtWithSpectrum(
                trackId = if (external != null) null else now.trackId,
                bitmap = external?.artwork ?: now.artwork.takeIf { external == null },
                playing = playing,
                source = if (external != null) VisualizerSpectrumSource else PlayerSpectrumSource,
                settings = settings,
                showSpectrum = settings.artVisualizer && (external == null || sessions.sessions.any { it.error == null }),
                modifier = Modifier
                    .aspectRatio(1f)
                    .onGloballyPositioned { onArtBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = artAlpha() },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (external == null) {
                IconButton(onClick = { now.trackId?.let(app.library::toggleFavorite) }, enabled = now.trackId != null) {
                    Crossfade(favorite, label = "heart") { on ->
                        Icon(
                            if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (on) "즐겨찾기에서 빼기" else "즐겨찾기에 추가",
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }

        SeekBar(
            position = { position.longValue },
            durationMs = duration,
            badge = if (external == null) format?.codec else null,
            onSeek = if (external != null) app.external::seekTo else app.player::seekTo,
        )

        Transport(
            playing = playing,
            onPrevious = if (external != null) app.external::previous else app.player::previous,
            onToggle = if (external != null) app.external::toggle else app.player::toggle,
            onNext = if (external != null) app.external::next else app.player::next,
        )

        VolumeRow()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (external == null) {
                IconButton(onClick = app.player::cycleRepeat) {
                    Icon(
                        if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "반복",
                        tint = if (now.repeatMode == Player.REPEAT_MODE_OFF) colors.ink.copy(alpha = 0.45f) else colors.ink,
                    )
                }
                IconButton(onClick = app.player::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "셔플", tint = if (now.shuffle) colors.ink else colors.ink.copy(alpha = 0.45f))
                }
            }
            IconButton(onClick = onOpenEq) {
                Icon(Icons.Rounded.Tune, contentDescription = "재생 EQ", tint = if (eq.enabled && profile != null) colors.ink else colors.ink.copy(alpha = 0.45f))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "더 보기") }
                GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("비주얼라이저 색 · 표시") },
                        onClick = {
                            menu = false
                            visualizer = true
                        },
                    )
                    if (external == null) {
                        DropdownMenuItem(
                            text = { Text("대기열 보기") },
                            enabled = now.queue.isNotEmpty(),
                            onClick = {
                                menu = false
                                queue = true
                            },
                        )
                        val trackId = now.trackId
                        if (trackId != null) {
                            val lists = library.playlists.filter { it.name != LibraryRepository.FAVORITES }
                            if (lists.isNotEmpty()) HorizontalDivider()
                            lists.forEach { playlist ->
                                DropdownMenuItem(
                                    text = { Text("'${playlist.name}'에 추가") },
                                    onClick = {
                                        menu = false
                                        app.library.addToPlaylist(playlist.name, trackId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    external != null -> if (eq.systemEq && eq.enabled) "${appName(external.packageName)} · 재생 EQ 적용 중" else appName(external.packageName)
                    report.claim?.claimed == true -> "실시간 모드가 USB DAC을 점유 중이라 소리가 폰 스피커로 나간다"
                    else -> formatLine(format, processed = profile != null || (eq.enabled && eq.tubeEnabled))
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.glass(GlassShapes.chip).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (queue) {
        GlassBottomSheet(onDismiss = { queue = false }) {
            Text("대기열", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                now.queue.forEachIndexed { index, title ->
                    val current = index == now.queueIndex
                    Text(
                        title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { app.player.skipTo(index) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.tertiary else colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (visualizer) VisualizerSheet(app, onDismiss = { visualizer = false })
}

/**
 * Artwork with the spectrum laid over its lower half: a translucent wave that rises with the
 * music, bright enough to read on any cover because a soft shadow sits under it.
 */
@Composable
fun ArtWithSpectrum(
    trackId: Long?,
    bitmap: ImageBitmap?,
    playing: Boolean,
    source: SpectrumSource,
    settings: TrainerSettings,
    showSpectrum: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
) {
    // The artwork breathes with the music: full size while playing, slightly sunk when paused.
    val scale by animateFloatAsState(if (playing) 1f else 0.94f, spring(dampingRatio = 0.6f, stiffness = 260f), label = "art")
    Box(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Crossfade(targetState = trackId to bitmap, animationSpec = tween(420), label = "artwork") { (id, image) ->
            Artwork(trackId = id, size = 2000.dp, corner = corner, bitmap = image, modifier = Modifier.fillMaxSize())
        }
        if (showSpectrum) {
            val spectrum = rememberSpectrum(source, playing, SpectrumOptions.of(settings))
            val paint = visualizerPaint(settings, onArtwork = true)
            val layers = SpectrumLayers.of(settings)
            Canvas(Modifier.matchParentSize().clip(RoundedCornerShape(corner))) {
                val area = Rect(Offset(0f, size.height * 0.5f), Size(size.width, size.height * 0.5f))
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), startY = area.top, endY = area.bottom),
                    topLeft = area.topLeft,
                    size = area.size,
                )
                drawSpectrumLayers(spectrum, layers, area, paint, fillAlpha = 0.42f, lineAlpha = 0.95f)
            }
        }
    }
}

/** The position slider with the times under it and, between them, what kind of file it is. */
@Composable
fun SeekBar(position: () -> Long, durationMs: Long, badge: String?, onSeek: (Long) -> Unit) {
    val colors = GlassTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1)
    // Read here, in the seek bar's own scope: each tick recomposes this bar and its times, nothing else.
    val positionMs = position()
    Column {
        GlassSlider(
            value = if (dragging) dragValue else (positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                time(if (dragging) (dragValue * duration).toLong() else positionMs),
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (badge != null) {
                Text(
                    badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.Center).glass(RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(
                time(durationMs),
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Previous, play or pause, next - bare icons, as in the reference, the middle one larger. */
@Composable
fun Transport(playing: Boolean, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.FastRewind, contentDescription = "이전 곡", modifier = Modifier.size(46.dp))
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(76.dp)) {
            Crossfade(playing, animationSpec = tween(160), label = "play") { on ->
                Icon(
                    if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (on) "일시정지" else "재생",
                    modifier = Modifier.size(62.dp),
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.FastForward, contentDescription = "다음 곡", modifier = Modifier.size(46.dp))
        }
    }
}

/** The phone's media volume, which is what the hardware keys move too. */
@Composable
fun VolumeRow() {
    val colors = GlassTheme.colors
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LaunchedEffect(Unit) {
        // The keys change it behind the slider's back; there is no cheaper way to hear about it.
        while (true) {
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            delay(500)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.VolumeMute, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.ink.copy(alpha = 0.8f))
        GlassSlider(
            value = volume.toFloat(),
            onValueChange = { v ->
                val next = v.roundToInt()
                if (next != volume) {
                    volume = next
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
                }
            },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.ink.copy(alpha = 0.8f))
    }
}

/** "16 bit 44.1 kHz 753 kbps → EQ · 16 bit 44.1 kHz": what the file is, and what is sent on. */
private fun formatLine(format: PlaybackFormat?, processed: Boolean): String {
    if (format == null) return if (processed) "EQ 켜짐" else "EQ 꺼짐"
    val rate = (format.sampleRate / 100).let { if (it % 10 == 0) "${it / 10}" else "${it / 10}.${it % 10}" } + "\u00A0kHz"
    val source = listOfNotNull(format.bits?.let { "$it\u00A0bit" }, rate, format.bitrateKbps?.let { "$it\u00A0kbps" }).joinToString(" ")
    return "$source\n→ ${if (processed) "EQ · " else ""}16\u00A0bit $rate"
}
