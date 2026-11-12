package mmm.app.ui.settings

import mmm.app.ui.components.rememberTabScrollState
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.BuildConfig
import mmm.app.MmmApplication
import mmm.app.data.PlaybackEq
import mmm.app.data.TrainerSettings
import mmm.app.data.VisualizerColor
import mmm.app.ui.components.glass.GroupCard
import mmm.app.ui.components.glass.GroupDivider
import mmm.app.ui.components.glass.LocalPanelInset
import mmm.app.ui.components.glass.PillTabs
import mmm.app.ui.components.glass.backdropBlurSupported
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.theme.ThemeMode
import mmm.app.ui.visual.VisualizerSheet
import mmm.app.ui.settings.BackgroundSheet
import mmm.app.ui.components.glass.GlassTopBar
import mmm.app.ui.components.glass.SectionHeader
import mmm.app.ui.components.glass.SettingRow
import mmm.app.ui.components.glass.Switch

/**
 * Everything that is a setting rather than a control, in one place, grouped as the reference
 * groups its own: where the EQ reaches, how the displays draw, and what the app is.
 *
 * An exercise's settings are not here; they are on that exercise's page, next to its start button.
 */
@Composable
fun SettingsScreen(app: MmmApplication, onOpenEarphones: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val eq by app.eq.state.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val active = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    val context = LocalContext.current
    var visualizerSheet by rememberSaveable { mutableStateOf(false) }
    var backgroundSheet by rememberSaveable { mutableStateOf(false) }

    val askRecord = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The EQ works without it; only the spectrum of the other app needs it.
        app.eq.update { it.copy(systemEq = true) }
        app.syncSystemEq()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberTabScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassTopBar(title = "설정")

        SectionHeader("재생 EQ가 걸리는 곳")
        GroupCard {
            SettingRow(
                title = "이 앱의 플레이어",
                subtitle = "항상 걸린다. 필터를 변환 없이 그대로 쓴다.",
            )
            GroupDivider()
            SettingRow(
                title = "다른 앱의 소리 (Spotify 등)",
                subtitle = "곡을 재생한다고 알려 주는 앱의 소리에 EQ를 붙인다. 캡처가 아니라서 소리가 겹치지 않고 블루투스에서도 된다.",
                trailing = {
                    Switch(
                        checked = eq.systemEq,
                        onCheckedChange = { on ->
                            if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                askRecord.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                app.eq.update { it.copy(systemEq = on) }
                                app.syncSystemEq()
                            }
                        },
                    )
                },
            )
            GroupDivider()
            SettingRow(
                title = "폰 전체 출력 (실험)",
                subtitle = "알려 주지 않는 앱까지 포함해 폰에서 나가는 소리 전체에 붙인다. 기기에 따라 막혀 있거나 다른 EQ 앱과 겹칠 수 있다.",
                trailing = {
                    Switch(
                        checked = eq.systemGlobal,
                        onCheckedChange = { on -> app.eq.update { it.copy(systemGlobal = on) } },
                        enabled = eq.systemEq,
                    )
                },
            )
            GroupDivider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("다른 앱용 밴드 해상도", style = MaterialTheme.typography.bodyLarge)
                PillTabs(
                    labels = PlaybackEq.SYSTEM_BANDS.map { "1/$it 옥타브" },
                    selected = PlaybackEq.SYSTEM_BANDS.indexOf(eq.systemBandsPerOctave).coerceAtLeast(0),
                    onSelect = { index -> app.eq.update { it.copy(systemBandsPerOctave = PlaybackEq.SYSTEM_BANDS[index]) } },
                )
                Text(
                    "안드로이드는 다른 앱의 소리에 필터를 직접 걸 수 없고 대역별 음량만 허용한다. 그래서 EQ 곡선을 촘촘한 밴드로 옮겨 넣는다. " +
                        "기본은 가장 촘촘한 1/48 옥타브(약 480밴드)이고, 기기가 받지 못하면 받을 때까지 한 단계씩 낮춘다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("재생 EQ")
        GroupCard {
            SettingRow(
                title = "쓰는 이어폰",
                subtitle = active?.name ?: "고른 이어폰 없음",
                onClick = onOpenEarphones,
            )
            GroupDivider()
            SettingRow(
                title = "자동 프리앰프",
                subtitle = "EQ의 가장 큰 부스트만큼 전체 음량을 내려 소리가 찢어지지 않게 한다. 끄면 프리앰프 슬라이더 값만 쓴다.",
                trailing = { Switch(checked = eq.autoHeadroom, onCheckedChange = { on -> app.eq.update { it.copy(autoHeadroom = on) } }) },
            )
        }

        SectionHeader("화면")
        GroupCard {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("테마", style = MaterialTheme.typography.bodyLarge)
                PillTabs(
                    labels = ThemeMode.entries.map { it.label },
                    selected = ThemeMode.entries.indexOf(settings.themeMode),
                    onSelect = { index -> app.settings.setThemeMode(ThemeMode.entries[index]) },
                    fill = true,
                )
            }
            GroupDivider()
            SettingRow(
                title = "유리 뒤 실시간 흐림",
                subtitle = if (backdropBlurSupported) {
                    "하단 패널이 그 뒤로 지나가는 화면을 실제로 흐려 보여 준다. 끄면 불투명한 유리로 바뀐다 - 화면이 깨져 보이거나 느리면 끈다."
                } else {
                    "안드로이드 12부터 쓸 수 있다. 이 기기에서는 불투명한 유리로 그린다."
                },
                trailing = {
                    Switch(
                        checked = settings.backdropBlur && backdropBlurSupported,
                        onCheckedChange = app.settings::setBackdropBlur,
                        enabled = backdropBlurSupported,
                    )
                },
            )
            GroupDivider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("비주얼라이저 해상도", style = MaterialTheme.typography.bodyLarge)
                PillTabs(
                    labels = TrainerSettings.VISUALIZER_POINTS.map { "1/${it / 10} 옥타브" },
                    selected = TrainerSettings.VISUALIZER_POINTS.indexOf(settings.visualizerPoints).coerceAtLeast(0),
                    onSelect = { index -> app.settings.setVisualizerPoints(TrainerSettings.VISUALIZER_POINTS[index]) },
                )
                Text(
                    "20 Hz–20 kHz를 ${settings.visualizerPoints}개 점으로 그린다. 촘촘할수록 긴 구간을 분석해 저역의 음 하나하나가 갈라져 보이고, 그만큼 계산이 는다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GroupDivider()
            SettingRow(
                title = "배경",
                subtitle = if (settings.backgroundMode == mmm.app.data.BackgroundMode.ALBUM) "앨범아트 색 따라가기" else "사용자 지정 그라데이션",
                onClick = { backgroundSheet = true },
            )
            GroupDivider()
            SettingRow(
                title = "비주얼라이저 표시",
                subtitle = "색 ${settings.visualizerColor.label} · 높이 ${settings.visualizerScaling.label} · 가중 ${settings.visualizerWeighting.label}",
                onClick = { visualizerSheet = true },
            )
            GroupDivider()
            SettingRow(
                title = "앨범아트 위에 스펙트럼",
                trailing = { Switch(checked = settings.artVisualizer, onCheckedChange = app.settings::setArtVisualizer) },
            )
            GroupDivider()
            SettingRow(
                title = "검사·훈련을 카드로 보기",
                trailing = { Switch(checked = settings.hubCards, onCheckedChange = app.settings::setHubCards) },
            )
        }

        SectionHeader("Spotify 탭")
        GroupCard {
            SettingRow(
                title = "재생 정보 접근",
                subtitle = "다른 앱이 무엇을 재생하는지 보려면 '알림 접근'을 허용해야 한다. 알림 내용은 읽지 않는다.",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        }

        SectionHeader("정보")
        GroupCard {
            SettingRow(title = "청음 훈련 v${BuildConfig.VERSION_NAME}", subtitle = "빌드 ${BuildConfig.BUILD_ID}")
        }
        Spacer(Modifier.height(LocalPanelInset.current))
    }

    if (visualizerSheet) VisualizerSheet(app, onDismiss = { visualizerSheet = false })
    if (backgroundSheet) BackgroundSheet(app, onDismiss = { backgroundSheet = false })
}
