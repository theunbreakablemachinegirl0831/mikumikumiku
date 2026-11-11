package mmm.app.ui.earphone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import mmm.app.ui.components.glass.Button
import mmm.app.ui.components.glass.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import mmm.app.ui.components.glass.FilterChip
import androidx.compose.material3.MaterialTheme
import mmm.app.ui.components.glass.OutlinedButton
import mmm.app.ui.components.glass.GlassTextField
import androidx.compose.material3.Scaffold
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.Switch
import androidx.compose.material3.Text
import mmm.app.ui.components.glass.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mmm.app.MmmApplication
import mmm.app.data.EarphoneConnection
import mmm.app.data.EarphoneProfile
import mmm.app.ui.components.glass.GlassTopBar
import mmm.eq.EqFilterType
import mmm.eq.EqProfile
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EarphoneScreen(app: MmmApplication, id: String, onBack: () -> Unit) {
    val vm: EarphoneViewModel = viewModel(key = "earphone-$id", factory = EarphoneViewModel.factory(app, id))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val earphones by app.earphones.earphones.collectAsStateWithLifecycle()
    val targets by app.earphones.targets.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val profile = earphones.firstOrNull { it.id == id }
    val context = LocalContext.current

    val pickMeasurement = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importMeasurement(uris)
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTarget(uri, displayName(context, uri))
    }
    val pickEq = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importEq(uri)
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
            GlassTopBar(title = profile?.name ?: "이어폰", onBack = onBack)
            if (profile == null) {
                Text("이 이어폰을 찾을 수 없다.")
                return@Column
            }

            ui.message?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            BasicsCard(profile, vm)
            MeasurementCard(profile, onPick = { pickMeasurement.launch(arrayOf("text/*", "application/octet-stream", "*/*")) })
            TargetCard(profile, targets, vm, onPick = { pickTarget.launch(arrayOf("text/*", "*/*")) })
            ResultCard(profile, ui, vm, onImportEq = { pickEq.launch(arrayOf("text/*", "*/*")) })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::use, enabled = profile.eqText != null) {
                    Text(if (settings.activeEarphoneId == id) "사용 중" else "이 이어폰 사용")
                }
                TextButton(onClick = { vm.delete(onBack) }) { Text("삭제") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicsCard(profile: EarphoneProfile, vm: EarphoneViewModel) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var note by remember(profile.id) { mutableStateOf(profile.note) }
    StepCard("이어폰") {
        GlassTextField(
            value = name,
            onValueChange = { name = it; if (it.isNotBlank()) vm.rename(it.trim()) },
            label = { Text("이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EarphoneConnection.entries.forEach { connection ->
                FilterChip(
                    selected = profile.connection == connection,
                    onClick = { vm.setConnection(connection) },
                    label = { Text(connection.label) },
                )
            }
        }
        if (profile.connection == EarphoneConnection.BLUETOOTH) {
            Text(
                "블루투스 이어폰은 실시간 모드에 쓸 수 없다 - USB DAC을 점유해야 원래 소리가 겹치지 않는다. " +
                    "훈련(음원 모드)에서는 EQ가 그대로 걸린다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        GlassTextField(
            value = note,
            onValueChange = { note = it; vm.setNote(it) },
            label = { Text("메모 (이어팁, 노이즈 캔슬링 모드 등)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MeasurementCard(profile: EarphoneProfile, onPick: () -> Unit) {
    StepCard("1. 측정치 (B&K 5128)") {
        Text(
            if (profile.hasMeasurement) "측정치가 들어 있다. 다시 가져오면 바뀐다." else "아직 측정치가 없다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "squig.link 등에서 받은 텍스트 파일(주파수 / dB)을 고른다. 왼쪽·오른쪽 파일을 함께 고르면 평균을 쓴다. " +
                "예: earphonesarchive.squig.link/data/SeeAudio Bravo L.txt",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onPick) { Text("측정 파일 가져오기") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(profile: EarphoneProfile, targets: List<String>, vm: EarphoneViewModel, onPick: () -> Unit) {
    var tilt by remember(profile.id) { mutableFloatStateOf(profile.tiltDbPerOctave.toFloat()) }
    StepCard("2. 타깃") {
        if (targets.isEmpty()) {
            Text(
                "타깃이 없다. 측정치와 같은 장비(5128) 기준 타깃이어야 한다 - " +
                    "\"squig.link에서 받기\"를 누르면 earphonesarchive의 타깃을 모두 받는다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            targets.forEach { name ->
                FilterChip(
                    selected = profile.targetName == name,
                    onClick = { vm.chooseTarget(name) },
                    label = { Text(name) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::fetchTargets) { Text("squig.link에서 받기") }
            OutlinedButton(onClick = onPick) { Text("파일 가져오기") }
        }

        Text("기울기: ${format(tilt.toDouble(), 2)} dB/oct", style = MaterialTheme.typography.bodyMedium)
        GlassSlider(
            value = tilt,
            onValueChange = { tilt = (it * 4).roundToInt() / 4f },
            onValueChangeFinished = { vm.setTilt(tilt.toDouble()) },
            valueRange = -2f..2f,
            steps = 15,
        )
        Text(
            "음수면 저음이 늘고 고음이 줄어든다(1 kHz 기준으로 돌린다). 취향 조절용이다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("고역 세밀 보정 (20 kHz까지)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "끄면 10 kHz까지만 정밀하게 맞추고 그 위는 넓은 셸프로 기울기만 맞춘다. " +
                        "10 kHz 위의 좁은 봉우리·골은 삽입 깊이와 내 귀에 따라 달라서, 켜면 오히려 어긋날 수 있다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = profile.fullRange, onCheckedChange = vm::setFullRange)
        }
    }
}

@Composable
private fun ResultCard(profile: EarphoneProfile, ui: EarphoneUiState, vm: EarphoneViewModel, onImportEq: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    StepCard("3. EQ") {
        ui.busy?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(it)
            }
        }

        val curves = buildList {
            ui.measured?.let { add(GraphCurve("측정치", it, MEASURED)) }
            ui.target?.let { add(GraphCurve("타깃", it, TARGET, dashed = true)) }
            ui.predicted?.let { add(GraphCurve("EQ 적용 예상", it, PREDICTED)) }
        }
        if (curves.isNotEmpty()) ResponseGraph(curves)

        val eq = ui.eq
        if (eq == null) {
            Text(
                "측정치와 타깃이 모두 있으면 자동으로 EQ를 만든다. AutoEq 등에서 받은 EQ 파일을 바로 써도 된다.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                buildString {
                    append(if (ui.imported) "가져온 EQ" else "자동 EQ")
                    append(" · 필터 ").append(eq.filters.size).append("개")
                    append(" · 프리앰프 ").append(format(eq.preampDb, 1)).append(" dB")
                    val before = ui.rmsBeforeDb
                    val after = ui.rmsAfterDb
                    if (before != null && after != null) {
                        append(" · 타깃과의 차이 ").append(format(before, 1)).append(" → ").append(format(after, 1)).append(" dB RMS")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            FilterTable(eq)
            if (ui.imported) {
                Text(
                    "가져온 EQ는 만든 사람의 측정 장비와 타깃 기준이다. 그래프의 예상 곡선은 여기 넣은 측정치에 그 EQ를 걸었을 때의 모양이다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportEq) { Text("AutoEq 파일 가져오기") }
            if (ui.imported) OutlinedButton(onClick = vm::autoFit) { Text("자동 EQ로") }
        }
        if (profile.eqText != null) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(profile.eqText)) }) {
                Text("EQ 텍스트 복사 (Equalizer APO 형식)")
            }
        }
    }
}

@Composable
private fun FilterTable(eq: EqProfile) {
    Column {
        eq.filters.forEach { filter ->
            val type = when (filter.type) {
                EqFilterType.PEAK -> "PK "
                EqFilterType.LOW_SHELF -> "LSC"
                EqFilterType.HIGH_SHELF -> "HSC"
            }
            Text(
                String.format(
                    Locale.ROOT,
                    "%s %7.0f Hz  %+5.1f dB  Q %.2f",
                    type,
                    filter.frequency,
                    filter.gainDb,
                    filter.q,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private val MEASURED = Color(0xFF43A047)
private val TARGET = Color(0xFF9E9E9E)
private val PREDICTED = Color(0xFF1E88E5)

private fun format(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "타깃"
