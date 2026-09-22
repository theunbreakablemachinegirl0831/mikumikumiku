package mmm.app.ui.live

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.live.LiveOutput
import mmm.app.live.LiveSession
import mmm.app.live.LiveStatus
import mmm.app.ui.theme.ScreenHeader
import mmm.source.capture.CaptureState
import mmm.source.usb.UsbAudioReport
import mmm.training.Curriculum
import mmm.training.ExerciseFamily

/**
 * Setting up the live mode, one step at a time: claim the DAC, prove the stream with a test tone,
 * start capture, then pick an exercise.
 *
 * Each step is its own card with its own evidence, so when something does not work it is plain
 * which step it was.
 */
@Composable
fun LiveScreen(app: MmmApplication, onBack: () -> Unit, onOpenFamily: (ExerciseFamily) -> Unit) {
    val live = app.live
    val context = LocalContext.current
    LaunchedEffect(Unit) { live.start() }

    val report by live.usb.report.collectAsStateWithLifecycle()
    val status by live.status.collectAsStateWithLifecycle()
    val capture by live.capture.state.collectAsStateWithLifecycle()

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            live.startCapture(result.resultCode, data)
        }
    }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) projection.launch(live.capture.permissionIntent())
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The capture service runs without it; only its notification is hidden.
    }

    val startCapture = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val canRecord = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (canRecord) {
            projection.launch(live.capture.permissionIntent())
        } else {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
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
            ScreenHeader(title = "실시간 모드", onBack = onBack)
            Text(
                "Spotify 등에서 재생 중인 음악을 가로채 USB DAC으로만 내보내고, 거기에 과제의 처리를 건다. " +
                    "DAC을 점유하면 다른 앱의 원래 소리는 폰 스피커로 밀려나므로, 폰 볼륨은 줄여 둔다.",
                style = MaterialTheme.typography.bodySmall,
            )

            status.message?.let { NoteCard(it) }

            DacCard(report, status, live)
            ToneCard(status, live)
            CaptureCard(status, capture, live, startCapture)
            ExercisesCard(ready = live.capturing, onOpenFamily = onOpenFamily)
        }
    }
}

@Composable
private fun DacCard(report: UsbAudioReport, status: LiveStatus, live: LiveSession) {
    val claimed = report.claim?.claimed == true
    StepCard("1. USB DAC 점유") {
        val selected = report.selected
        Text(
            when {
                selected == null -> report.message ?: "연결된 USB DAC이 없다"
                else -> "${selected.label} (${selected.identity})"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        report.chosenAlternate?.let {
            Text("형식: ${it.describe} · ${status.sampleRate / 1000.0} kHz", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow2 {
            when {
                selected == null -> OutlinedButton(onClick = live.usb::refresh) { Text("다시 찾기") }
                !report.permissionGranted -> Button(onClick = { live.usb.requestPermission() }) { Text("USB 접근 허용") }
                !claimed -> Button(onClick = live::claim) { Text("DAC 점유") }
                else -> OutlinedButton(onClick = live::release) { Text("점유 해제") }
            }
        }
        if (claimed) {
            Text(
                "점유 후에는 폰의 다른 소리가 전부 내장 스피커로 나와야 정상이다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToneCard(status: LiveStatus, live: LiveSession) {
    val playing = status.output == LiveOutput.TEST_TONE
    StepCard("2. 테스트 톤") {
        Text(
            "DAC 볼륨을 먼저 낮춘다. 핑크 노이즈를 ${LiveSession.TONE_RMS_DBFS.toInt()} dBFS로 DAC에만 보낸다. " +
                "이어폰에서 '쉬-' 소리가 끊김 없이 나면 USB 출력이 동작하는 것이다.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow2 {
            if (playing) {
                OutlinedButton(onClick = live::stopOutput) { Text("톤 정지") }
            } else {
                FilledTonalButton(onClick = live::startTestTone, enabled = status.sampleRate > 0) { Text("톤 재생") }
            }
        }
        if (playing) StreamLine(status)
    }
}

@Composable
private fun CaptureCard(status: LiveStatus, capture: CaptureState, live: LiveSession, onStart: () -> Unit) {
    val active = status.output == LiveOutput.CAPTURE
    StepCard("3. 실시간 캡처") {
        Text(
            "캡처 권한을 허용한 뒤 Spotify에서 음악을 튼다. 음악이 DAC(이어폰)에서 나오고, 폰 스피커에서도 " +
                "원래 소리가 난다면 정상이다 - 폰 볼륨만 줄이면 된다.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow2 {
            if (active) {
                OutlinedButton(onClick = live::stopOutput) { Text("캡처 정지") }
            } else {
                Button(onClick = onStart, enabled = status.sampleRate > 0) { Text("캡처 시작") }
            }
        }
        if (active) {
            Text(
                when (capture) {
                    CaptureState.Idle -> "대기 중"
                    CaptureState.Starting -> "시작하는 중"
                    is CaptureState.Running -> "캡처 중 · ${capture.sampleRate / 1000.0} kHz · 지연 약 ${capture.latencyMs} ms"
                    is CaptureState.Failed -> "실패: ${capture.reason}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            StreamLine(status)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExercisesCard(ready: Boolean, onOpenFamily: (ExerciseFamily) -> Unit) {
    StepCard("4. 과제") {
        Text(
            if (ready) "음악이 흐르는 동안 과제를 고른다." else "캡처가 돌고 있어야 고를 수 있다.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Curriculum.families.forEach { family ->
                OutlinedButton(onClick = { onOpenFamily(family) }, enabled = ready) { Text(family.displayName) }
            }
        }
    }
}

/** One line of stream numbers: enough to tell "silent because broken" from "silent because starved". */
@Composable
private fun StreamLine(status: LiveStatus) {
    val s = status.stream
    val bus = if (s.highSpeed) "HS" else "FS"
    val depth = LiveSession.framesToMs(s.bufferedFrames, s.sampleRate)
    Text(
        buildString {
            append(if (s.playing) "재생" else "준비")
            append(" · ").append(bus).append(" ").append(s.packetsPerSecond).append("/s")
            append(" · 패킷 ").append(s.packets)
            append(" · 버퍼 ").append(depth).append("ms")
            append(" · 끊김 ").append(s.underruns)
            if (s.failedUrbs > 0 || s.failedPackets > 0) {
                append(" · 오류 ").append(s.failedUrbs).append("/").append(s.failedPackets)
            }
            if (s.fasterCorrections + s.slowerCorrections > 0) {
                append(" · 보정 +").append(s.fasterCorrections).append("/-").append(s.slowerCorrections)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun NoteCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
