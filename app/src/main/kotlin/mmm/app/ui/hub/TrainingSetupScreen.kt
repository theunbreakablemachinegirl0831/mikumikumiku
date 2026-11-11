package mmm.app.ui.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.data.FamilyProgress
import mmm.app.data.SourceChoice
import mmm.app.data.TrainerSettings
import mmm.app.ui.home.PracticeRangeCard
import mmm.app.ui.theme.FilterChip
import mmm.app.ui.theme.GlassButton
import mmm.app.ui.theme.GroupCard
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.ScreenHeader
import mmm.app.ui.theme.SectionHeader
import mmm.app.ui.theme.SettingRow
import mmm.app.ui.theme.Switch
import mmm.app.ui.theme.WhiteSlider
import mmm.app.ui.theme.glass
import mmm.training.Curriculum
import mmm.training.ExerciseFamily
import mmm.training.PracticeFocus
import kotlin.math.roundToInt

/**
 * One exercise's own page: what it trains, where the learner is, and the settings that apply to
 * it - then the button that starts it. Settings that mean nothing for this exercise are not
 * shown: the frequency range and the filter's gain and Q belong to the two spectral ones.
 */
@Composable
fun TrainingSetupScreen(app: MmmApplication, family: ExerciseFamily, onBack: () -> Unit, onStart: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val progress by app.progress.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val levels = Curriculum.levels(family)
    val own = progress[family] ?: FamilyProgress()
    val level = own.level.coerceIn(levels.indices)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Without a persisted grant the file is unreadable after the next restart, and the
            // setting would silently point at nothing.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.settings.setSource(SourceChoice.File(uri.toString(), displayName(context, uri)))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(title = family.displayName, onBack = onBack, subtitle = family.skill)

        Row(
            Modifier.fillMaxWidth().glass().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) {
                Icon(iconFor(family), contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lv ${level + 1}/${levels.size} · ${levels[level].name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (own.trials > 0) "지금까지 ${own.trials}문제 · 정답률 ${(own.accuracy * 100).toInt()}%" else "아직 푼 문제가 없다",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { (level + 1).toFloat() / levels.size },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }

        GlassButton("시작", onClick = onStart, modifier = Modifier.fillMaxWidth(), primary = true)

        SectionHeader("음원")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.source is SourceChoice.PinkNoise,
                onClick = { app.settings.setSource(SourceChoice.PinkNoise) },
                label = { Text("핑크 노이즈") },
            )
            FilterChip(
                selected = settings.source is SourceChoice.File,
                onClick = { pickFile.launch(arrayOf("audio/*")) },
                label = { Text(if (settings.source is SourceChoice.File) "다른 파일…" else "음악 파일…") },
            )
        }
        Text(
            when (val source = settings.source) {
                SourceChoice.PinkNoise -> "모든 대역을 고르게 자극하므로 스펙트럼 과제의 가장 정직한 시험이다."
                is SourceChoice.File -> "${source.name} - 곡에서 대역이 가장 고르게 찬 구간을 골라 쓴다."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )

        if (family in PracticeFocus.APPLIES_TO) {
            PracticeRangeCard(current = settings.practiceRange, onChange = app.settings::setPracticeRange)
            SectionHeader("필터")
            FilterCard(settings, app)
        }

        SectionHeader("공통")
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
                WhiteSlider(
                    value = settings.excerptSeconds.toFloat(),
                    onValueChange = { app.settings.setExcerptSeconds(it.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1,
                )
                Text(
                    "길수록 음악의 여러 부분을 비교할 수 있지만 문제를 만드는 시간이 늘어난다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
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
                label = if (gain == null) "게인: 레벨 기본값" else "게인: ±${plain(gain)} dB",
                checked = gain != null,
                onChange = { on -> app.settings.setFilterGain(if (on) TrainerSettings.DEFAULT_GAIN_DB else null) },
            )
            if (gain != null) {
                val min = TrainerSettings.GAIN_MIN_DB
                val max = TrainerSettings.GAIN_MAX_DB
                val step = TrainerSettings.GAIN_STEP_DB
                WhiteSlider(
                    value = gain.toFloat(),
                    // Snapped to the step, so the stored value is one the label can show exactly.
                    onValueChange = { v -> app.settings.setFilterGain((v / step).roundToInt() * step) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = ((max - min) / step).roundToInt() - 1,
                )
                Text("컷 문제에서는 같은 크기로 깎는다.", style = MaterialTheme.typography.bodySmall)
            }

            val q = settings.filterQ
            SwitchRow(
                label = if (q == null) "Q: 레벨 기본값" else "Q: ${plain(q)}",
                checked = q != null,
                onChange = { on -> app.settings.setFilterQ(if (on) TrainerSettings.DEFAULT_Q else null) },
            )
            if (q != null) {
                val values = TrainerSettings.Q_VALUES
                val index = values.indexOfFirst { it >= q }.let { if (it < 0) values.lastIndex else it }
                WhiteSlider(
                    value = index.toFloat(),
                    onValueChange = { v -> app.settings.setFilterQ(values[v.roundToInt().coerceIn(values.indices)]) },
                    valueRange = 0f..values.lastIndex.toFloat(),
                    steps = values.size - 2,
                )
                Text(
                    "대역폭 약 ${((TrainerSettings.octavesForQ(q) * 100).roundToInt() / 100.0)} 옥타브. " +
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

private fun plain(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "선택한 음원"
