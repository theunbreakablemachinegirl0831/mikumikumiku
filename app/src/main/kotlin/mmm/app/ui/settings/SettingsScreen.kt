package mmm.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import mmm.app.ui.theme.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import mmm.app.ui.theme.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.BuildConfig
import mmm.app.MmmApplication
import mmm.app.data.TrainerSettings
import mmm.app.ui.theme.GroupCard
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.ScreenHeader
import mmm.app.ui.theme.SectionHeader
import mmm.app.ui.theme.SettingRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: MmmApplication, onBack: (() -> Unit)?, onOpenEarphones: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val active = earphones.firstOrNull { it.id == settings.activeEarphoneId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = "설정", onBack = onBack)

        SectionHeader("이어폰")
        GroupCard {
            SettingRow(
                title = "이어폰 보정",
                subtitle = when {
                    active == null -> "고른 이어폰 없음 - 측정치로 EQ를 만든다"
                    active.eqText == null -> "${active.name} · 아직 EQ 없음"
                    else -> active.name
                },
                onClick = onOpenEarphones,
            )
            GroupDivider()
            SettingRow(
                title = "이어폰 EQ 적용",
                subtitle = "훈련, 플레이어, 실시간 모드, 다른 앱 EQ에 모두 걸린다",
                trailing = { Switch(checked = settings.eqEnabled, onCheckedChange = app.settings::setEqEnabled) },
            )
        }

        SectionHeader("훈련")
        GroupCard {
            SettingRow(
                title = "라우드니스 매칭",
                subtitle = "켜 두는 것이 정상이다. 끄면 부스트된 밴드가 그냥 더 큰 소리가 되어 음량만으로 맞힐 수 있다 - 시연용으로만 끈다",
                trailing = { Switch(checked = settings.loudnessMatched, onCheckedChange = app.settings::setLoudnessMatched) },
            )
            GroupDivider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("발췌 길이", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${settings.excerptSeconds}초", style = MaterialTheme.typography.bodyLarge)
                }
                val range = TrainerSettings.EXCERPT_RANGE
                Slider(
                    value = settings.excerptSeconds.toFloat(),
                    onValueChange = { app.settings.setExcerptSeconds(it.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1,
                )
                Text(
                    "길수록 음악의 여러 부분을 비교할 수 있지만 문제를 만드는 시간이 늘어난다. 다음 과제부터 적용된다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("필터 (밴드 식별 · 레조넌스)")
        FilterCard(settings, app)

        SectionHeader("정보")
        GroupCard {
            SettingRow(
                title = "청음 훈련 v${BuildConfig.VERSION_NAME}",
                subtitle = "빌드 ${BuildConfig.BUILD_ID}",
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Gain and Q for the filter exercises.
 *
 * Each is either "follow the ladder" or a fixed value, rather than a slider that is always live:
 * the ladder's own values are a deliberate difficulty curve, and the switch makes it obvious when
 * that curve is being overridden.
 */
@Composable
private fun FilterCard(settings: TrainerSettings, app: MmmApplication) {
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기본값은 레벨마다 정해진 값이다 (밴드 식별은 +12 dB부터 시작). 직접 지정하면 모든 " +
                    "레벨에 같은 값이 쓰이고, 레벨이 오르면 격자와 보기 수만 어려워진다.",
                style = MaterialTheme.typography.bodySmall,
            )

            val gain = settings.filterGainDb
            SwitchRow(
                label = if (gain == null) "게인: 레벨 기본값" else "게인: ±${formatDb(gain)} dB",
                checked = gain != null,
                onChange = { on ->
                    app.settings.setFilterGain(if (on) TrainerSettings.DEFAULT_GAIN_DB else null)
                },
            )
            if (gain != null) {
                val min = TrainerSettings.GAIN_MIN_DB
                val max = TrainerSettings.GAIN_MAX_DB
                val step = TrainerSettings.GAIN_STEP_DB
                Slider(
                    value = gain.toFloat(),
                    onValueChange = { v ->
                        // Snap to the step, so the stored value is one the label can show exactly.
                        app.settings.setFilterGain((v / step).roundToInt() * step)
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = ((max - min) / step).roundToInt() - 1,
                )
                Text(
                    "컷 문제에서는 같은 크기로 깎는다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val q = settings.filterQ
            SwitchRow(
                label = if (q == null) "Q: 레벨 기본값" else "Q: ${formatQ(q)}",
                checked = q != null,
                onChange = { on -> app.settings.setFilterQ(if (on) TrainerSettings.DEFAULT_Q else null) },
            )
            if (q != null) {
                val values = TrainerSettings.Q_VALUES
                val index = values.indexOfFirst { it >= q }.let { if (it < 0) values.lastIndex else it }
                Slider(
                    value = index.toFloat(),
                    onValueChange = { v -> app.settings.setFilterQ(values[v.roundToInt().coerceIn(values.indices)]) },
                    valueRange = 0f..values.lastIndex.toFloat(),
                    steps = values.size - 2,
                )
                Text(
                    "대역폭 약 ${formatOctaves(TrainerSettings.octavesForQ(q))} 옥타브. " +
                        "Q가 낮을수록 넓게 올라간다. 밴드 식별에서 격자 간격보다 넓게 잡으면 옆 밴드까지 " +
                        "같이 올라가 정답이 모호해진다 - 1/3 옥타브 격자라면 Q 4 이상이 무난하다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatDb(db: Double): String =
    if (db == kotlin.math.floor(db)) db.toInt().toString() else db.toString()

private fun formatQ(q: Double): String =
    if (q == kotlin.math.floor(q)) q.toInt().toString() else q.toString()

private fun formatOctaves(octaves: Double): String =
    ((octaves * 100).roundToInt() / 100.0).toString()
