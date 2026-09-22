package mmm.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import mmm.app.ui.theme.ScreenHeader
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: MmmApplication, onBack: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(title = "설정", onBack = onBack)

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "라우드니스 매칭",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.loudnessMatched,
                            onCheckedChange = app.settings::setLoudnessMatched,
                        )
                    }
                    Text(
                        "켜 두는 것이 정상이다. 끄면 부스트된 밴드가 그냥 더 큰 소리가 되어, 음량만으로 " +
                            "정답을 맞힐 수 있게 된다. 왜 매칭이 필요한지 직접 들어보는 시연용으로만 끈다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "발췌 길이: ${settings.excerptSeconds}초",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val range = TrainerSettings.EXCERPT_RANGE
                    Slider(
                        value = settings.excerptSeconds.toFloat(),
                        onValueChange = { app.settings.setExcerptSeconds(it.roundToInt()) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = range.last - range.first - 1,
                    )
                    Text(
                        "길수록 음악의 여러 부분을 비교할 수 있지만 문제를 만드는 시간이 늘어난다. " +
                            "다음 과제를 시작할 때부터 적용된다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "청음 훈련 v${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_ID}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
