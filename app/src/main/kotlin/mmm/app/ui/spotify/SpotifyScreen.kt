package mmm.app.ui.spotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import mmm.app.ui.components.glass.GlassMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mmm.app.MmmApplication
import mmm.app.ui.theme.GlassTheme
import mmm.app.sessioneq.SessionEqService
import mmm.app.sessioneq.SessionEqStatus
import mmm.app.spotify.ExternalNowPlaying
import mmm.app.spotify.ExternalPlayback
import mmm.app.ui.player.ArtWithSpectrum
import mmm.app.ui.player.SeekBar
import mmm.app.ui.player.Transport
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.LocalPanelInset
import mmm.app.ui.components.glass.Switch
import mmm.app.ui.components.glass.glass
import mmm.app.ui.visual.VisualizerSpectrumSource
import mmm.app.ui.visual.visualizerColor

/**
 * Spotify, in the shape of the player: what it is playing, with the same artwork, spectrum and
 * transport, and under them whether the EQ has reached its audio.
 *
 * Two separate things make this work, and each can be missing on its own. The EQ gets to
 * Spotify's sound through the session service, which needs only its switch. What is playing is
 * read from Spotify's media session, which Android shows only to apps the listener has granted
 * notification access.
 */
@Composable
fun SpotifyScreen(app: MmmApplication, onOpenEq: () -> Unit) {
    val context = LocalContext.current
    val external by app.external.state.collectAsStateWithLifecycle()
    val eq by app.eq.state.collectAsStateWithLifecycle()
    val status by SessionEqService.status.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    // Coming back from the system settings is the moment access may have changed.
    LifecycleResumeEffect(Unit) {
        app.external.refresh()
        onPauseOrDispose { }
    }
    val askRecord = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The EQ works without it; only the spectrum needs it.
        app.eq.update { it.copy(systemEq = true) }
        app.syncSystemEq()
    }
    val setSystemEq = { on: Boolean ->
        if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askRecord.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            app.eq.update { it.copy(systemEq = on) }
            app.syncSystemEq()
        }
    }
    val openSpotify = {
        context.packageManager.getLaunchIntentForPackage(ExternalPlayback.SPOTIFY)
            ?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        Unit
    }
    val openAccess = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(appName(external.packageName), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "더 보기") }
                GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Spotify 열기") }, onClick = { menu = false; openSpotify() })
                    DropdownMenuItem(text = { Text("재생 EQ 열기") }, onClick = { menu = false; onOpenEq() })
                    DropdownMenuItem(text = { Text("재생 정보 접근 설정") }, onClick = { menu = false; openAccess() })
                }
            }
        }

        if (external.hasSession) {
            Playing(
                app = app,
                external = external,
                points = settings.visualizerPoints,
                showSpectrum = settings.artVisualizer && status.sessions.any { it.error == null },
                spectrumColor = visualizerColor(settings.visualizerColor, onArtwork = true),
            )
        } else {
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (external.access) {
                    Text("재생 중인 앱이 없다", style = MaterialTheme.typography.titleMedium)
                    Text("Spotify에서 곡을 틀면 여기에 곡 정보와 조작 버튼이 나온다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("곡 정보를 보려면 접근 허용이 필요하다", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "안드로이드는 다른 앱이 무엇을 재생하는지를 '알림 접근'을 허용한 앱에만 알려 준다. 이 앱은 알림 내용은 읽지 않고 " +
                            "재생 정보만 본다. 허용하지 않아도 아래 EQ는 그대로 걸린다.\n" +
                            "설정에서 스위치가 회색으로 막혀 있으면: 앱 정보 → 오른쪽 위 ⋮ → '제한된 설정 허용'을 먼저 누른다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    GlassButton("접근 허용하러 가기", onClick = openAccess, primary = true)
                }
                GlassButton("Spotify 열기", onClick = openSpotify)
            }
        }

        EqStatus(eq.enabled, eq.systemEq, status, setSystemEq)
        Spacer(Modifier.height(LocalPanelInset.current))
    }
}

@Composable
private fun Playing(app: MmmApplication, external: ExternalNowPlaying, points: Int, showSpectrum: Boolean, spectrumColor: Color) {
    // The session reports its position only when it jumps; in between it is worked out from the clock.
    var position by remember { mutableLongStateOf(external.positionNow()) }
    LaunchedEffect(external.positionMs, external.positionAt, external.isPlaying) {
        while (true) {
            position = external.positionNow()
            delay(500)
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ArtWithSpectrum(
            trackId = null,
            bitmap = external.artwork,
            playing = external.isPlaying,
            source = VisualizerSpectrumSource,
            points = points,
            showSpectrum = showSpectrum,
            spectrumColor = spectrumColor,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
    }
    Column {
        Text(
            external.title ?: "제목 없음",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(external.artist, external.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    SeekBar(positionMs = position, durationMs = external.durationMs, badge = null, onSeek = app.external::seekTo)
    Transport(
        playing = external.isPlaying,
        onPrevious = app.external::previous,
        onToggle = app.external::toggle,
        onNext = app.external::next,
    )
}

/** Whether the EQ has reached the other app's audio, in the place the player shows its format. */
@Composable
private fun EqStatus(masterOn: Boolean, systemEq: Boolean, status: SessionEqStatus, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("다른 앱에 재생 EQ 걸기", style = MaterialTheme.typography.bodyLarge)
                val attached = status.sessions.filter { it.error == null }
                Text(
                    when {
                        !systemEq -> "꺼짐 - Spotify는 원래 소리로 나온다"
                        !masterOn -> "붙어 있지만 재생 EQ가 꺼져 있다"
                        attached.isEmpty() -> "기다리는 중 - Spotify에서 곡을 한 번 멈췄다가 다시 튼다"
                        else -> attached.joinToString(" · ") { session ->
                            (if (session.sessionId == 0) "폰 전체 출력" else appName(session.packageName)) + " ${session.bandCount}밴드"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = systemEq, onCheckedChange = onChange)
        }
        status.sessions.mapNotNull { it.error }.distinct().forEach { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (systemEq && status.sessions.none { it.error == null }) {
            Text(
                "오래 기다려도 안 붙으면 Spotify가 세션을 알려 주지 않는 것이다. 설정 → 재생 EQ → '폰 전체 출력에 적용'을 켠다.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = GlassTheme.colors.inkMuted,
            )
        }
    }
}

internal fun appName(pkg: String?): String = when (pkg) {
    null, ExternalPlayback.SPOTIFY -> "Spotify"
    "com.google.android.apps.youtube.music" -> "YouTube Music"
    "com.iloen.melon" -> "멜론"
    else -> pkg
}
