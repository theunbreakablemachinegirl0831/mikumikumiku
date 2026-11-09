package mmm.app.ui.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import mmm.app.ui.theme.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.sessioneq.SessionEqService
import mmm.app.ui.earphone.GraphCurve
import mmm.app.ui.earphone.ResponseGraph
import mmm.app.ui.theme.Card
import mmm.eq.FrequencyResponse
import mmm.eq.GraphicEqBands

/**
 * The earphone EQ on Spotify (or any app that announces its audio session), through
 * [SessionEqService]. Shows what got attached, the PEQ against the band curve actually sent, and
 * the attached app's spectrum.
 */
@Composable
fun SpotifyTab(app: MmmApplication) {
    val context = LocalContext.current
    val status by SessionEqService.status.collectAsStateWithLifecycle()
    val correction by app.activeCorrection.collectAsStateWithLifecycle()
    val askRecord = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The EQ works without it; only the spectrum needs it.
        SessionEqService.start(context)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("다른 앱에 EQ 걸기", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Spotify 등에서 재생하는 소리에 이어폰 EQ를 바로 건다. 캡처가 아니라 소리가 겹치지 않고, " +
                                "블루투스·에어팟에서도 된다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = status.running,
                        onCheckedChange = { on ->
                            if (!on) {
                                SessionEqService.stop(context)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                SessionEqService.start(context)
                            } else {
                                askRecord.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
                if (correction == null) {
                    Text(
                        "지금은 걸 EQ가 없다 - 이어폰 탭에서 이어폰을 고르고 EQ를 켠다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (status.running) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("붙어 있는 앱", style = MaterialTheme.typography.titleMedium)
                    if (status.sessions.isEmpty()) {
                        Text(
                            "아직 없다. Spotify에서 곡을 재생하면 여기에 나타난다. 이미 재생 중이었다면 한 번 정지했다가 다시 재생한다. " +
                                "그래도 안 나타나면 Spotify가 세션을 알려 주지 않는 것이니 아래 전역 모드를 써 본다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    status.sessions.forEach { session ->
                        Text(
                            buildString {
                                append(if (session.sessionId == 0) "전역 출력" else appName(session.packageName))
                                if (session.bandCount > 0) append(" · 밴드 ${session.bandCount}개")
                                session.error?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (session.error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("전역 모드 (실험)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "앱이 세션을 알려 주지 않을 때 폰 전체 출력에 붙인다. 기기에 따라 막혀 있거나, 다른 EQ 앱과 겹칠 수 있다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = status.global,
                            onCheckedChange = { on ->
                                SessionEqService.command(
                                    context,
                                    if (on) SessionEqService.ACTION_GLOBAL_ON else SessionEqService.ACTION_GLOBAL_OFF,
                                )
                            },
                        )
                    }
                    OutlinedButton(onClick = {
                        context.packageManager.getLaunchIntentForPackage(SPOTIFY)?.let {
                            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Spotify 열기") }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("스펙트럼", style = MaterialTheme.typography.titleMedium)
                    SpectrumView(
                        playing = status.sessions.any { it.error == null },
                        eq = correction,
                        source = VisualizerSpectrumSource,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        correction?.let { eq ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PEQ와 실제로 들어간 곡선", style = MaterialTheme.typography.titleMedium)
                    val grid = FrequencyResponse.logGrid(20.0, 20000.0, 48)
                    val bands = GraphicEqBands.from(eq)
                    ResponseGraph(
                        listOf(
                            GraphCurve("원래 PEQ", eq.response(grid), MaterialTheme.colorScheme.primary),
                            GraphCurve("밴드 EQ (${bands.bands.size}밴드)", bands.realizedResponse(grid), MaterialTheme.colorScheme.tertiary, dashed = true),
                        ),
                    )
                    Text(
                        "다른 앱에는 필터 대신 촘촘한 밴드로 같은 곡선을 그려 넣는다. 밴드 곡선은 각 밴드의 이득이 그대로 " +
                            "유지된다고 본 예상치이고, 실제로는 기기의 구현에 따라 이웃 밴드끼리 부드럽게 이어진다. " +
                            "100~200 Hz 아래는 기기의 FFT 크기 때문에 덜 세밀하다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private const val SPOTIFY = "com.spotify.music"

private fun appName(pkg: String): String = when (pkg) {
    SPOTIFY -> "Spotify"
    "com.google.android.apps.youtube.music" -> "YouTube Music"
    "com.iloen.melon" -> "멜론"
    else -> pkg
}
