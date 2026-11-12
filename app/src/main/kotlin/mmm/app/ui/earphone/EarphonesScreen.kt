package mmm.app.ui.earphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import mmm.app.ui.components.glass.Button
import mmm.app.ui.components.glass.Card
import androidx.compose.material3.MaterialTheme
import mmm.app.ui.components.glass.GlassTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import mmm.app.ui.components.glass.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mmm.app.MmmApplication
import mmm.app.data.EarphoneProfile
import mmm.app.ui.components.glass.GlassTopBar

/** The listener's earphones: which one is in use, whether its EQ is on, and a way to add more. */
@Composable
fun EarphonesScreen(app: MmmApplication, onBack: (() -> Unit)?, onOpen: (String) -> Unit, onSearch: () -> Unit) {
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassTopBar(title = "이어폰", onBack = onBack)
            Text(
                "이어폰마다 공개된 B&K 5128 측정치를 넣고 타깃을 고르면, 그 타깃에 맞추는 EQ를 만든다. " +
                    "고른 이어폰이 재생 EQ의 자동 EQ와 청력 검사의 기준이 된다.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (earphones.isEmpty()) {
                Text("아직 등록한 이어폰이 없다.", style = MaterialTheme.typography.bodyMedium)
            }
            earphones.forEach { earphone ->
                EarphoneRow(
                    earphone = earphone,
                    active = earphone.id == settings.activeEarphoneId,
                    onSelect = { app.settings.setActiveEarphone(earphone.id) },
                    onOpen = { onOpen(earphone.id) },
                )
            }

            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
                Text("측정치 찾아서 추가 (squig.link)")
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("직접 추가 (측정 파일을 가지고 있을 때)", style = MaterialTheme.typography.titleMedium)
                    GlassTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("이름 (예: See Audio Bravo)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val name = newName.trim()
                            scope.launch {
                                val created = app.earphones.create(name)
                                newName = ""
                                onOpen(created.id)
                            }
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text("추가") }
                }
            }
        }
    }
}

@Composable
private fun EarphoneRow(earphone: EarphoneProfile, active: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(earphone.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(earphone.connection.label)
                        append(" · ")
                        append(
                            when {
                                earphone.eqText == null && !earphone.hasMeasurement -> "측정치 없음"
                                earphone.eqText == null -> "EQ 없음"
                                earphone.importedEq -> "가져온 EQ"
                                else -> "자동 EQ · ${earphone.targetName ?: "타깃"}"
                            }
                        )
                        if (earphone.note.isNotBlank()) append(" · ").append(earphone.note)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
