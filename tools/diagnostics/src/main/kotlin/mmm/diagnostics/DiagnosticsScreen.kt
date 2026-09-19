package mmm.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.audio.OutputRoute
import mmm.source.capture.CaptureState
import mmm.source.capture.CaptureVerdict

@Composable
public fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onStart: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("실시간 캡처 진단", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "다른 앱 오디오가 실제로 잡히는지, 원음을 죽일 수 있는지, 지연이 얼마인지 측정한다.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                VerdictCard(state.verdict)
                UsbCard(state, viewModel)
                RouteSelector(state.route, onSelect = viewModel::selectRoute)
                Controls(state, onStart = onStart, viewModel = viewModel)
                VolumeCard(state)
                MutingExperimentCard(state)
                MetersCard(state)
                PlayingCard(state)
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: CaptureVerdict) {
    val tint = when (verdict.status) {
        CaptureVerdict.Status.HEALTHY -> Color(0xFF1B5E20)
        CaptureVerdict.Status.SILENT, CaptureVerdict.Status.FAILED -> Color(0xFFB71C1C)
        CaptureVerdict.Status.DEGRADED -> Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(verdict.headline, style = MaterialTheme.typography.titleMedium)
            Text(verdict.detail, style = MaterialTheme.typography.bodyMedium)
            verdict.suggestions.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The USB DAC experiment: can we take the device away from Android?
 *
 * This is the question that decides whether writing a native isochronous streamer is worth it, so
 * it is reported in full - what the descriptors say, what the claim returned, and above all
 * whether Android's own USB output disappeared afterwards. A claim that succeeds while the
 * platform keeps its route means both of us are feeding the DAC, which solves nothing.
 */
@Composable
private fun UsbCard(state: DiagnosticsUiState, viewModel: DiagnosticsViewModel) {
    val usb = state.usb
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB DAC 배타 점유", style = MaterialTheme.typography.titleMedium)

            if (usb.candidates.isEmpty()) {
                Text(
                    usb.message ?: "연결된 USB 오디오 장치가 없다. DAC을 USB로 연결한다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                usb.candidates.forEach { candidate ->
                    val isSelected = candidate.deviceName == usb.selected?.deviceName
                    Text(
                        "${if (isSelected) "▶ " else "  "}${candidate.label} (${candidate.identity})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            usb.selected?.let { candidate ->
                Mono("USB 오디오 규격", candidate.function.spec.label)
                Mono("스트리밍 인터페이스", candidate.function.streamingInterfaceNumbers.joinToString())
                val rates = candidate.function.advertisedRates
                Mono(
                    "지원 샘플레이트",
                    if (rates.isEmpty()) "클럭 소스에서 조회 필요 (UAC 2.0)"
                    else rates.joinToString(" / ") { "${it / 1000}k" },
                )
                candidate.function.outputAlternates.forEach {
                    Text("· ${it.describe}", style = MaterialTheme.typography.bodySmall)
                }
            }

            usb.chosenAlternate?.let {
                Mono("선택된 설정", it.describe)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::requestUsbPermission,
                    enabled = usb.candidates.isNotEmpty() || !usb.permissionGranted,
                ) { Text("USB 권한") }
                Button(
                    onClick = viewModel::claimUsbExclusively,
                    enabled = usb.selected != null && !usb.holdingExclusively,
                ) { Text("배타 점유") }
                OutlinedButton(
                    onClick = viewModel::releaseUsbClaim,
                    enabled = usb.claim != null,
                ) { Text("해제") }
            }

            usb.claim?.let { claim ->
                Text(
                    when {
                        claim.exclusive -> "성립: Android 재생이 USB를 떠났다. 이제 DAC은 우리 것이다."
                        claim.claimed -> "부분 성공: 인터페이스는 잡았지만 Android가 아직 USB로 재생한다."
                        else -> "실패: 인터페이스를 점유하지 못했다."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(claim.detail, style = MaterialTheme.typography.bodySmall)
                Mono("실제 재생 경로", claim.routedOutputLabel ?: "확인 불가")
                if (claim.androidStillListsUsbOutput) {
                    Text(
                        "장치 목록에 USB가 남아있는 것은 정상이다 - 케이블이 꽂혀 있으니 Android는 " +
                            "계속 알고 있다. 판정은 위의 '실제 재생 경로'로 한다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (usb.androidOutputsBefore.isNotEmpty()) {
                Text("점유 전 Android 출력:", style = MaterialTheme.typography.bodySmall)
                usb.androidOutputsBefore.forEach {
                    Text("  · $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (usb.androidOutputsAfter.isNotEmpty()) {
                Text("점유 후 Android 출력:", style = MaterialTheme.typography.bodySmall)
                usb.androidOutputsAfter.forEach {
                    Text("  · $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "아직 소리는 나지 않는다. isochronous 전송은 Java USB API에 없어서 네이티브가 " +
                    "필요하고, 그건 Android가 장치를 놓아준다는 게 확인된 다음에 쓸 값어치가 있다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RouteSelector(selected: OutputRoute, onSelect: (OutputRoute) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("출력 라우팅 전략", style = MaterialTheme.typography.titleMedium)
            OutputRoute.entries.forEach { route ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = route == selected,
                        onClick = { onSelect(route) },
                        enabled = route.implemented,
                        label = { Text(route.displayName) },
                    )
                }
                Text(route.explanation, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "전략은 캡처를 시작하기 전에 고른다. 시작 후 바꾸려면 중지하고 다시 시작한다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Controls(
    state: DiagnosticsUiState,
    onStart: () -> Unit,
    viewModel: DiagnosticsViewModel,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = state.capture !is CaptureState.Running,
                ) { Text("캡처 시작") }
                OutlinedButton(
                    onClick = viewModel::stop,
                    enabled = state.capture is CaptureState.Running,
                ) { Text("중지") }
            }

            ToggleRow(
                label = "미디어 볼륨 0으로 (전략 A 실험)",
                checked = state.mediaMuted,
                onChange = viewModel::setMediaMuted,
            )
            ToggleRow(
                label = "통화 모드로 전환 (MODE_IN_COMMUNICATION)",
                checked = state.communicationMode,
                onChange = viewModel::setCommunicationMode,
            )
            ToggleRow(
                label = "아티팩트 걸기 (+12 dB @ 1 kHz)",
                checked = state.artifactOn,
                enabled = state.capture is CaptureState.Running,
                onChange = viewModel::setArtifact,
            )
            Text(
                "아티팩트를 켰을 때 소리가 확 변하면 처리 경로가 귀까지 도달한 것이다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun VolumeCard(state: DiagnosticsUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("볼륨 실측값", style = MaterialTheme.typography.titleMedium)
            Mono("미디어 스트림", "${state.mediaVolume} / ${state.maxMediaVolume}")
            Mono("처리음 스트림 (${state.route.displayName})", "${state.outputVolume} / ${state.maxOutputVolume}")
            state.volumeProblem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "요청한 값이 아니라 기기가 실제로 들고 있는 값이다. 음소거를 켰는데 미디어가 0이 " +
                    "아니면 볼륨 변경 자체가 거부되거나 무시된 것이고, 캡처 문제와는 다른 얘기다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MutingExperimentCard(state: DiagnosticsUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("전략 A 실험 결과", style = MaterialTheme.typography.titleMedium)
            Text(
                "순서: 캡처 시작 → 신호가 들어오는지 확인 → 미디어 볼륨 0 토글 → 신호가 살아있는지 확인.",
                style = MaterialTheme.typography.bodySmall,
            )
            when (state.mutingVerdict) {
                null -> Text("아직 결론 없음. 두 단계를 모두 거쳐야 판정된다.")
                true -> Text(
                    "성립: 미디어 볼륨을 0으로 내려도 캡처 신호가 살아있다. 전략 A를 기본값으로 쓸 수 있다.",
                    fontWeight = FontWeight.Bold,
                )
                false -> Text(
                    "불성립: 미디어 볼륨을 내리자 캡처도 무음이 되었다. 전략 B(출력 장치 분리)로 가야 한다.",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MetersCard(state: DiagnosticsUiState) {
    val telemetry = state.telemetry
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("측정값", style = MaterialTheme.typography.titleMedium)

            Meter("입력 (캡처)", telemetry.inputRmsDb)
            Meter("출력 (처리 후)", telemetry.outputRmsDb)

            val running = state.capture as? CaptureState.Running
            Mono("샘플레이트", running?.let { "${it.sampleRate} Hz · ${it.channels}ch" } ?: "-")
            Mono("왕복 지연", running?.let { "약 ${it.latencyMs} ms" } ?: "-")
            Mono("출력 장치", running?.outputDevice ?: "기본")
            Mono("언더런 / 오버런", "${telemetry.underrunFrames} / ${telemetry.overrunFrames} 프레임")
            Mono("처리한 프레임", "${telemetry.framesProcessed}")
            Mono("라우드니스 보정", String.format("%.2f dB", telemetry.levelCorrectionDb))
            Mono("경과", "${state.runningMs / 1000} s")
        }
    }
}

@Composable
private fun Meter(label: String, db: Double) {
    // -60..0 dBFS across the bar; anything quieter is indistinguishable from silence here.
    val fraction = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (db <= -120.0) "-∞ dBFS" else String.format("%.1f dBFS", db),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun Mono(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PlayingCard(state: DiagnosticsUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("현재 재생 중인 스트림", style = MaterialTheme.typography.titleMedium)
            if (state.playing.isEmpty()) {
                Text(
                    "없음. 아무것도 재생하지 않으면 무음은 당연한 결과이니 먼저 음악을 틀어야 한다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.playing.forEach { stream ->
                    Text(
                        "· ${stream.usageLabel}${if (stream.capturedByUs) " (캡처 대상)" else " (캡처 안 함)"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Android는 어느 앱인지까지는 알려주지 않으므로 스트림 종류만 표시한다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
