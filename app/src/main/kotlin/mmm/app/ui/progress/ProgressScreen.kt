package mmm.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.data.FamilyProgress
import mmm.app.ui.theme.ScreenHeader
import mmm.training.Curriculum

@Composable
fun ProgressScreen(app: MmmApplication, onBack: () -> Unit) {
    val progress by app.progress.progress.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(title = "진행도", onBack = onBack)

            val all = Curriculum.families.map { progress[it] ?: FamilyProgress() }
            val trials = all.sumOf { it.trials }
            val correct = all.sumOf { it.correct }
            Text(
                if (trials == 0) "아직 푼 문제가 없다."
                else "전체 ${trials}문제 · 정답 ${correct} (${correct * 100 / trials}%)",
                style = MaterialTheme.typography.bodyMedium,
            )

            Curriculum.families.forEach { family ->
                val p = progress[family] ?: FamilyProgress()
                val levels = Curriculum.levels(family)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                family.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { app.progress.reset(family) }, enabled = p.trials > 0) {
                                Text("초기화")
                            }
                        }
                        // The bar tracks the best level reached, not the current one: a bad run
                        // should not look like lost progress.
                        LinearProgressIndicator(
                            progress = { (p.bestLevel + 1).toFloat() / levels.size },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            "현재 Lv ${p.level + 1} · 최고 Lv ${p.bestLevel + 1} / ${levels.size}" +
                                if (p.trials > 0) {
                                    " · ${p.trials}문제, 정답률 ${(p.accuracy * 100).toInt()}%"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
