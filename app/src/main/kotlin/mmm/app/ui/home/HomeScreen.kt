package mmm.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.BuildConfig
import mmm.app.MmmApplication
import mmm.app.data.FamilyProgress
import mmm.app.data.SourceChoice
import mmm.training.Curriculum
import mmm.training.ExerciseFamily

@Composable
fun HomeScreen(
    app: MmmApplication,
    onOpenFamily: (ExerciseFamily) -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val progress by app.progress.progress.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("청음 훈련", style = MaterialTheme.typography.headlineMedium)
            Text(
                "v${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_ID}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            SourceCard(
                current = settings.source,
                onPinkNoise = { app.settings.setSource(SourceChoice.PinkNoise) },
                onPickFile = { pickFile.launch(arrayOf("audio/*")) },
            )

            Text("과제", style = MaterialTheme.typography.titleMedium)
            Curriculum.families.forEach { family ->
                FamilyCard(
                    family = family,
                    progress = progress[family] ?: FamilyProgress(),
                    onClick = { onOpenFamily(family) },
                )
            }

            LiveModeCard()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenProgress) { Text("진행도") }
                OutlinedButton(onClick = onOpenSettings) { Text("설정") }
            }
        }
    }
}

@Composable
private fun SourceCard(current: SourceChoice, onPinkNoise: () -> Unit, onPickFile: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("음원", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current is SourceChoice.PinkNoise,
                    onClick = onPinkNoise,
                    label = { Text("핑크 노이즈") },
                )
                FilterChip(
                    selected = current is SourceChoice.File,
                    onClick = onPickFile,
                    label = { Text(if (current is SourceChoice.File) "다른 파일…" else "파일 선택…") },
                )
            }
            Text(
                when (current) {
                    SourceChoice.PinkNoise ->
                        "모든 대역을 고르게 자극하므로 스펙트럼 과제의 가장 정직한 시험이다."
                    is SourceChoice.File ->
                        "${current.name} - 실제 음악으로 옮겨가는 연습이다. 30초 지점부터 잘라 쓴다."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FamilyCard(family: ExerciseFamily, progress: FamilyProgress, onClick: () -> Unit) {
    val levels = Curriculum.levels(family)
    val current = levels[progress.level.coerceIn(levels.indices)]
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(family.displayName, style = MaterialTheme.typography.titleMedium)
            Text(family.skill, style = MaterialTheme.typography.bodySmall)
            Text(
                buildString {
                    append("Lv ").append(progress.level + 1).append("/").append(levels.size)
                    append(" · ").append(current.name)
                    if (progress.bestLevel > progress.level) {
                        append(" · 최고 Lv ").append(progress.bestLevel + 1)
                    }
                    if (progress.trials > 0) {
                        append(" · 정답률 ").append((progress.accuracy * 100).toInt()).append("%")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Where live capture will go once the USB output path can play sound. Shown now, disabled, so it
 * is clear the mode exists and is not forgotten - rather than appearing out of nowhere later.
 */
@Composable
private fun LiveModeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("실시간 모드 (준비 중)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Spotify 등에서 재생 중인 음악으로 훈련한다. USB DAC 배타 점유는 확인되었고, " +
                    "DAC으로 소리를 보내는 네이티브 출력이 남았다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "선택한 음원"
