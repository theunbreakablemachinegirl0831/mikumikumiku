package mmm.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.BuildConfig
import mmm.app.MmmApplication
import mmm.app.data.FamilyProgress
import mmm.app.data.SourceChoice
import mmm.app.ui.theme.FilterChip
import mmm.app.ui.theme.Glass
import mmm.app.ui.theme.GroupCard
import mmm.app.ui.theme.GroupDivider
import mmm.app.ui.theme.ScreenHeader
import mmm.app.ui.theme.SectionHeader
import mmm.app.ui.theme.SettingRow
import mmm.app.ui.theme.glass
import mmm.training.Curriculum
import mmm.training.ExerciseFamily

@Composable
fun HomeScreen(
    app: MmmApplication,
    onOpenFamily: (ExerciseFamily) -> Unit,
    onOpenProgress: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenEarphones: () -> Unit,
) {
    val progress by app.progress.progress.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val activeEarphone = earphones.firstOrNull { it.id == settings.activeEarphoneId }
    val context = LocalContext.current

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(title = "청음 훈련", subtitle = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_ID}")

        // Which earphone the training is heard through, as a pill that opens the earphone tab.
        Row(
            Modifier.glass(Glass.pill).clickable(onClick = onOpenEarphones).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                when {
                    activeEarphone == null -> "이어폰 보정 없음"
                    !settings.eqEnabled || activeEarphone.eqText == null -> "${activeEarphone.name} · EQ 꺼짐"
                    else -> "${activeEarphone.name} · EQ 켜짐"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

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

        PracticeRangeCard(current = settings.practiceRange, onChange = app.settings::setPracticeRange)

        SectionHeader("과제")
        GroupCard {
            Curriculum.families.forEachIndexed { index, family ->
                if (index > 0) GroupDivider()
                FamilyRow(family, progress[family] ?: FamilyProgress(), onClick = { onOpenFamily(family) })
            }
        }

        SectionHeader("더 보기")
        GroupCard {
            SettingRow(
                title = "실시간 모드 (USB DAC)",
                subtitle = "Spotify 등에서 재생 중인 음악으로 훈련한다",
                onClick = onOpenLive,
            )
            GroupDivider()
            SettingRow(title = "진행도", subtitle = "과제별 레벨과 정답률", onClick = onOpenProgress)
        }
        Text(
            "빌드 ${BuildConfig.BUILD_ID}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FamilyRow(family: ExerciseFamily, progress: FamilyProgress, onClick: () -> Unit) {
    val levels = Curriculum.levels(family)
    val level = progress.level.coerceIn(levels.indices)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) {
            Icon(iconFor(family), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(family.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append("Lv ").append(level + 1).append("/").append(levels.size)
                    append(" · ").append(levels[level].name)
                    if (progress.trials > 0) append(" · 정답률 ").append((progress.accuracy * 100).toInt()).append("%")
                },
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
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
}

private fun iconFor(family: ExerciseFamily): ImageVector = when (family) {
    ExerciseFamily.BAND_ID -> Icons.Rounded.Equalizer
    ExerciseFamily.RESONANCE -> Icons.Rounded.Sensors
    ExerciseFamily.BANDWIDTH -> Icons.Rounded.Tune
    ExerciseFamily.DISTORTION -> Icons.Rounded.BlurOn
    ExerciseFamily.COMPRESSION -> Icons.Rounded.Compress
    ExerciseFamily.REVERB -> Icons.Rounded.SurroundSound
    ExerciseFamily.SPECTRAL_BALANCE -> Icons.Rounded.GraphicEq
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "선택한 음원"
