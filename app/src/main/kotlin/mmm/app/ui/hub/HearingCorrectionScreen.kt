package mmm.app.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.earphone.GraphCurve
import mmm.app.ui.earphone.ResponseGraph
import mmm.app.ui.components.glass.FilterChip
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.GroupCard
import mmm.app.ui.components.glass.GroupDivider
import mmm.app.ui.components.glass.GlassTopBar
import mmm.app.ui.components.glass.SectionHeader
import mmm.app.ui.components.glass.SettingRow
import mmm.app.ui.components.glass.Switch
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.glass
import mmm.eq.EqProfile
import mmm.eq.FrequencyResponse
import mmm.eq.HearingPrescription
import mmm.eq.HearingRule
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hearing correction: the last hearing test, put through a prescription rule, becomes one more
 * layer of the playback EQ.
 *
 * The rules offered are the published linear ones. The screen says plainly why the current
 * clinical prescriptions are not among them, because picking "NAL-NL2" from a list and getting
 * something else would be worse than not having the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HearingCorrectionScreen(app: MmmApplication, onBack: () -> Unit, onOpenAudiometry: () -> Unit) {
    val state by app.eq.state.collectAsStateWithLifecycle()
    val hearing by app.eq.hearing.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassTopBar(title = "귀 응답 보정", onBack = onBack, subtitle = "청력 검사 결과로 내 귀에 맞는 EQ를 만든다")

        hearing.message?.let { message ->
            Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                GlassButton("순음 청력 검사 하러 가기", onClick = onOpenAudiometry, primary = true)
            }
        }

        GroupCard {
            SettingRow(
                title = "재생 EQ에 적용",
                subtitle = "이어폰 보정, PEQ, GEQ와 함께 더해져 재생 EQ의 한 층이 된다. 재생 EQ 화면의 그래프에 합쳐져 보인다.",
                trailing = {
                    Switch(
                        checked = state.hearingEnabled,
                        onCheckedChange = { on -> app.eq.update { it.copy(hearingEnabled = on, enabled = it.enabled || on) } },
                        enabled = hearing.filters.isNotEmpty() || state.hearingEnabled,
                    )
                },
            )
        }

        SectionHeader("처방식")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HearingRule.entries.forEach { rule ->
                FilterChip(
                    selected = state.hearingRule == rule,
                    onClick = { app.eq.update { it.copy(hearingRule = rule) } },
                    label = { Text(rule.label) },
                )
            }
        }
        Text(state.hearingRule.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))

        GroupCard {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("강도", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${(state.hearingStrength * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                }
                GlassSlider(
                    value = state.hearingStrength.toFloat(),
                    onValueChange = { v -> app.eq.update { it.copy(hearingStrength = (v * 20).roundToInt() / 20.0) } },
                    valueRange = 0f..1.5f,
                )
                Text("100%가 처방식 그대로다. 처음에는 낮게 시작해서 귀가 익으면 올린다.", style = MaterialTheme.typography.bodySmall)
            }
            GroupDivider()
            SettingRow(
                title = "말소리용 주파수 모양 포함",
                subtitle = "NAL-R과 POGO에는 말소리가 잘 들리도록 저역을 덜 주는 고정된 모양이 들어 있다. 음악에는 맞지 않아 " +
                    "기본은 끄고, 내 청력 손실이 요구하는 만큼만 쓴다.",
                trailing = {
                    Switch(
                        checked = state.hearingSpeechShape,
                        onCheckedChange = { on -> app.eq.update { it.copy(hearingSpeechShape = on) } },
                    )
                },
            )
        }

        val wanted = hearing.wanted
        if (wanted != null) {
            SectionHeader("결과")
            Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val applied = remember(hearing.filters) { EqProfile(hearing.filters, 0.0).response(FrequencyResponse.logGrid(20.0, 20000.0, 12)) }
                ResponseGraph(
                    listOf(
                        GraphCurve("처방식이 요구하는 이득", wanted, GlassTheme.colors.inkMuted, dashed = true),
                        GraphCurve("필터 ${hearing.filters.size}개로 맞춘 EQ", applied, MaterialTheme.colorScheme.tertiary),
                    ),
                )
                Text(
                    "이득은 중역(250 Hz–2 kHz) 평균을 0으로 놓고 본 모양이다. 전체 음량은 EQ가 아니라 볼륨의 몫이라서다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (hearing.left.isNotEmpty() || hearing.right.isNotEmpty()) {
            SectionHeader("검사 결과 (1 kHz 기준 상대 청력, +가 더 안 들림)")
            Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(
                    buildString {
                        append(String.format(Locale.ROOT, "%-8s%8s%8s\n", "Hz", "왼쪽", "오른쪽"))
                        for (f in HearingPrescription.FREQUENCIES) {
                            append(
                                String.format(
                                    Locale.ROOT, "%-8s%8s%8s\n",
                                    f.toInt().toString(),
                                    hearing.left[f]?.let { signedDb(it) } ?: "-",
                                    hearing.right[f]?.let { signedDb(it) } ?: "-",
                                )
                            )
                        }
                    }.trimEnd(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    buildString {
                        append("EQ 하나가 양쪽 귀에 같이 걸리므로 두 귀의 평균으로 계산한다.")
                        if (!hearing.calibrated) append(" 이어폰 측정치가 없어 이어폰 특성이 결과에 섞여 있다.")
                        if (!hearing.reliable) append(" 이 검사는 재검사 차이가 크거나 헛누름이 많았다 - 다시 검사하는 편이 좋다.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        SectionHeader("NAL-NL2 · DSL v5는 왜 없나")
        Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "두 처방은 작은 소리와 큰 소리에 서로 다른 이득을 준다(비선형). 그대로 따르려면 EQ가 아니라 대역별 압축기가 있어야 하고, " +
                    "청력도 절대값(dB HL)으로 알아야 하는데 폰과 이어폰으로는 1 kHz 기준 상대값까지만 잴 수 있다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "또 NAL-NL2는 허가받은 소프트웨어로만, DSL v5는 표와 소프트웨어로만 배포되어, 식으로 옮겨 적을 수 있는 형태가 아니다. " +
                    "비슷하게 지어낸 숫자를 그 이름으로 넣는 대신, 식이 전부 공개된 선형 처방만 넣었다. NAL-R은 NAL-NL 계열이 출발한 식이다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "의료 기기가 아니다. 청력 손실이 의심되면 이비인후과에서 정식 검사와 처방을 받는다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun signedDb(value: Double): String = (if (value > 0) "+" else "") + String.format(Locale.ROOT, "%.0f", value)
