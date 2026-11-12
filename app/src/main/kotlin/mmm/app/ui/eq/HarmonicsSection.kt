package mmm.app.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mmm.app.data.PlaybackEq
import mmm.app.ui.components.glass.FilterChip
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.GlassTextField
import mmm.app.ui.components.glass.Switch
import mmm.app.ui.components.glass.glass
import mmm.app.ui.theme.GlassTheme
import mmm.dsp.harmonics.HarmonicAlgorithm
import mmm.dsp.harmonics.HarmonicPreset
import mmm.dsp.harmonics.HarmonicProfile
import mmm.dsp.harmonics.HarmonicSettings
import mmm.dsp.harmonics.NosRolloff
import mmm.dsp.harmonics.TailParams
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The harmonic stage: a preset and how much of it, then everything the preset is made of - the
 * second and third harmonic, the algorithm that grows the higher orders from them, the even and
 * odd groups, any single order set by hand - and, apart from all of it, the NOS treble roll-off.
 *
 * Every harmonic level is on one scale: 0 dB is a harmonic 30 dB below a full-scale tone, the
 * most the stage makes, and -15 dB is 15 dB less. Touching any of them makes the preset "Custom";
 * the amount, the band trims and NOS do not, because they are not part of a preset's sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HarmonicsSection(state: PlaybackEq, update: ((PlaybackEq) -> PlaybackEq) -> Unit) {
    val h = state.harmonics
    val p = h.profile
    val set = { change: (HarmonicSettings) -> HarmonicSettings -> update { it.copy(harmonics = change(it.harmonics)) } }
    val edit = { change: (HarmonicProfile) -> HarmonicProfile -> set { it.edit(change) } }

    Card {
        Text(
            "진공관·DAC의 음색은 어떤 배음을 얼마나 더하느냐에서 온다. 프리셋을 고르고 양을 정한 뒤, 아래에서 차수별로 다듬는다.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HarmonicPreset.entries.forEach { preset ->
                FilterChip(
                    selected = h.preset == preset,
                    onClick = {
                        if (preset != HarmonicPreset.CUSTOM) {
                            update { it.copy(tubeEnabled = true, harmonics = it.harmonics.withPreset(preset)) }
                        }
                    },
                    label = { Text(preset.label, maxLines = 1) },
                )
            }
        }
        LabelledSlider(
            label = "프리셋 강도",
            value = if (h.intensityPercent == 0) "꺼짐" else "${h.intensityPercent} %",
            position = h.intensityPercent.toFloat(),
            range = 0f..100f,
            onChange = { v -> set { it.copy(intensityPercent = v.roundToInt().coerceIn(0, 100)) } },
        )
        Note("모든 차수의 진폭에 같은 비율을 곱한다. 50 %는 모든 배음이 6 dB 작아질 뿐 차수 사이 비율은 그대로다.")
    }

    Card {
        var selected by rememberSaveable { mutableIntStateOf(2) }
        val top = p.maxOrder.coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
        val order = selected.coerceIn(2, top)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차수별 레벨", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GlassButton(
                "모두 자동",
                onClick = { edit { it.copy(overrides = emptyMap()) } },
                enabled = p.overrides.isNotEmpty(),
            )
        }
        OrderGraph(p, selected = order, onSelect = { selected = it })
        val overridden = order in p.overrides
        DbControl(
            label = "H$order" + if (overridden) " (직접 지정)" else " (자동 생성)",
            value = p.levelDb(order).coerceIn(HarmonicSettings.MIN_DB, HarmonicSettings.MAX_DB),
            onChange = { v ->
                edit {
                    when (order) {
                        2 -> it.copy(h2Db = v, overrides = it.overrides - 2)
                        3 -> it.copy(h3Db = v, overrides = it.overrides - 3)
                        else -> it.copy(overrides = it.overrides + (order to v))
                    }
                }
            },
        )
        if (order > 3) {
            GlassButton(
                "H$order 자동으로 되돌리기",
                onClick = { edit { it.copy(overrides = it.overrides - order) } },
                enabled = overridden,
            )
        }
        Note("막대를 눌러 차수를 고른다. H2·H3이 기준이고, H4부터는 아래 알고리즘이 H2(짝수)와 H3(홀수)에서 만든다. 직접 지정한 차수는 진하게 표시된다.")
    }

    Card {
        Text("H2 · H3", style = MaterialTheme.typography.titleMedium)
        DbControl("H2 (2차, 따뜻함)", p.h2Db) { v -> edit { it.copy(h2Db = v) } }
        DbControl("H3 (3차, 선명함)", p.h3Db) { v -> edit { it.copy(h3Db = v) } }
    }

    Card {
        Text("고차 배음 생성", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HarmonicAlgorithm.entries.forEach { algorithm ->
                FilterChip(
                    selected = p.algorithm == algorithm,
                    onClick = { edit { it.copy(algorithm = algorithm) } },
                    label = { Text(algorithm.label, maxLines = 1) },
                )
            }
        }
        val t = p.tail
        val top = p.maxOrder.coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
        val setTail = { change: (TailParams) -> TailParams -> edit { it.copy(tails = it.tails + (it.algorithm to change(it.tail))) } }
        LabelledSlider(
            label = "Intensity",
            value = "${(t.intensity * 100).roundToInt()} %",
            position = t.intensity.toFloat(),
            onChange = { v -> setTail { it.copy(intensity = (v * 100).roundToInt() / 100.0) } },
        )
        LabelledSlider(
            label = "Slope",
            value = "${fmt(t.slopeDb)} dB/차수",
            position = t.slopeDb.toFloat(),
            range = 0f..HarmonicSettings.SLOPE_MAX_DB.toFloat(),
            onChange = { v -> setTail { it.copy(slopeDb = (v * 10).roundToInt() / 10.0) } },
        )
        if (p.algorithm.shaped) {
            LabelledSlider(
                label = "Center Harmonic",
                value = "H${t.centre}",
                position = t.centre.toFloat(),
                range = 4f..top.coerceAtLeast(5).toFloat(),
                onChange = { v -> setTail { it.copy(centre = v.roundToInt()) } },
            )
            LabelledSlider(
                label = "Curve Shape",
                value = fmt(t.shape),
                position = t.shape.toFloat(),
                range = HarmonicSettings.SHAPE_MIN.toFloat()..HarmonicSettings.SHAPE_MAX.toFloat(),
                onChange = { v -> setTail { it.copy(shape = (v * 10).roundToInt() / 10.0) } },
            )
        }
        LabelledSlider(
            label = "Maximum Harmonic Order",
            value = "H$top",
            position = top.toFloat(),
            range = HarmonicSettings.MIN_ORDER.toFloat()..HarmonicSettings.MAX_ORDER.toFloat(),
            onChange = { v -> edit { it.copy(maxOrder = v.roundToInt()) } },
        )
        DbControl("Output Limit", t.outputLimitDb) { v -> setTail { it.copy(outputLimitDb = v) } }
        Note(
            when (p.algorithm) {
                HarmonicAlgorithm.LINEAR -> "차수가 오를 때마다 Slope만큼 고르게 줄어든다."
                HarmonicAlgorithm.SINE -> "Center 근처에서 가장 빠르게 줄고 양 끝은 완만한 사인 곡선."
                HarmonicAlgorithm.ARCTANGENT -> "Center까지는 거의 유지되다 그 뒤로 꺾여 줄어드는 아크탄젠트 곡선."
                HarmonicAlgorithm.SIGMOID -> "Center를 경계로 계단처럼 떨어지는 시그모이드 곡선. Shape가 클수록 가파르다."
            } + " 알고리즘마다 설정이 따로 저장된다. Output Limit은 모든 배음을 합친 크기의 상한이다.",
        )
    }

    Card {
        Text("짝수 · 홀수 그룹", style = MaterialTheme.typography.titleMedium)
        DbControl("짝수 레벨", p.even.levelDb) { v -> edit { it.copy(even = it.even.copy(levelDb = v)) } }
        DbControl("짝수 기울기", p.even.slopeDb) { v -> edit { it.copy(even = it.even.copy(slopeDb = v)) } }
        HorizontalDivider(color = GlassTheme.colors.inkFaint.copy(alpha = 0.3f))
        DbControl("홀수 레벨", p.odd.levelDb) { v -> edit { it.copy(odd = it.odd.copy(levelDb = v)) } }
        DbControl("홀수 기울기", p.odd.slopeDb) { v -> edit { it.copy(odd = it.odd.copy(slopeDb = v)) } }
        LabelledSlider(
            label = "Even / Odd Balance",
            value = balanceText(p.balance),
            position = p.balance.toFloat(),
            range = -1f..1f,
            onChange = { v -> edit { it.copy(balance = (v * 100).roundToInt() / 100.0) } },
        )
        Note("기울기는 그룹의 마지막 차수에서 그 값만큼 낮아지도록 차수에 따라 고르게 더해진다. 균형은 진폭 비율로 한쪽을 줄인다.")
    }

    Card {
        Text("대역별 배음 양", style = MaterialTheme.typography.titleMedium)
        listOf("저역 20–200 Hz", "중역 200–2000 Hz", "고역 2–20 kHz").forEachIndexed { index, name ->
            DbControl(name, h.bandTrimsDb.getOrElse(index) { 0.0 }) { v ->
                set { s -> s.copy(bandTrimsDb = List(HarmonicSettings.BANDS) { i -> if (i == index) v else s.bandTrimsDb.getOrElse(i) { 0.0 } }) }
            }
        }
        Note("각 대역의 소리가 배음을 얼마나 만드는지. 고역은 접힘 잡음을 막기 위해 차수마다 나이퀴스트 아래에서만 만든다.")
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOS 고역 감쇠", style = MaterialTheme.typography.titleMedium)
                Text(
                    "12 kHz ${fmt(NosRolloff.responseDb(12000.0))} dB · 20 kHz ${fmt(NosRolloff.responseDb(20000.0))} dB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = h.nos,
                onCheckedChange = { on ->
                    set { s ->
                        val preset = when {
                            on && s.preset == HarmonicPreset.R2R -> HarmonicPreset.R2R_NOS
                            !on && s.preset == HarmonicPreset.R2R_NOS -> HarmonicPreset.R2R
                            else -> s.preset
                        }
                        s.copy(nos = on, preset = preset)
                    }
                },
            )
        }
        Note("오버샘플링 없는 R-2R DAC의 고역 감쇠를 흉내 낸 별도 필터다. 배음 양은 바꾸지 않는다.")
    }

    Text(
        "배음은 이 앱의 플레이어에서만 만들어진다. 다른 앱의 소리에는 안드로이드가 밴드 EQ만 허용한다.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GlassTheme.colors.inkMuted)
}

/**
 * A level from -15 to 0 dB in 0.1 dB steps, as a slider and a number that can be typed. Both
 * show the same stored value; a typed number is clamped to the range and rounded to the step
 * when the field is left or Done is pressed, and the field then shows what was kept.
 */
@Composable
internal fun DbControl(label: String, value: Double, onChange: (Double) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(fmt(value)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(value, editing) { if (!editing) text = fmt(value) }
    val commit = {
        text.replace(',', '.').toDoubleOrNull()?.let { onChange(HarmonicSettings.clampDb(it)) }
        text = fmt(value)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
            GlassTextField(
                value = text,
                onValueChange = { typed -> text = typed.filter { it.isDigit() || it == '-' || it == '.' || it == ',' }.take(6) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    focus.clearFocus()
                }),
                modifier = Modifier
                    .width(92.dp)
                    .onFocusChanged { state ->
                        if (editing && !state.isFocused) commit()
                        editing = state.isFocused
                    },
            )
            Text(" dB", style = MaterialTheme.typography.bodyMedium)
        }
        GlassSlider(
            value = value.toFloat().coerceIn(HarmonicSettings.MIN_DB.toFloat(), HarmonicSettings.MAX_DB.toFloat()),
            onValueChange = { onChange(HarmonicSettings.clampDb(it.toDouble())) },
            valueRange = HarmonicSettings.MIN_DB.toFloat()..HarmonicSettings.MAX_DB.toFloat(),
        )
    }
}

/** One bar per order, 2 to the maximum, from -15 dB at the floor to 0 dB at the top. */
@Composable
private fun OrderGraph(profile: HarmonicProfile, selected: Int, onSelect: (Int) -> Unit) {
    val colors = GlassTheme.colors
    val measurer = rememberTextMeasurer()
    val top = profile.maxOrder.coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
    val orders = (2..top).toList()
    val pick by rememberUpdatedState(onSelect)
    val labelStyle = TextStyle(color = colors.inkMuted, fontSize = 10.sp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics { contentDescription = "차수별 배음 레벨 그래프" }
            .pointerInput(orders.size) {
                detectTapGestures { at ->
                    val index = (at.x / (size.width.toFloat() / orders.size)).toInt().coerceIn(0, orders.size - 1)
                    pick(orders[index])
                }
            },
    ) {
        val label = measurer.measure("H16", labelStyle)
        val bottom = size.height - label.size.height - 4f * density
        val slot = size.width / orders.size
        val bar = slot * 0.6f
        orders.forEachIndexed { i, order ->
            val db = profile.levelDb(order)
            val level = if (db <= HarmonicProfile.OFF) 0f else ((db - HarmonicSettings.MIN_DB) / -HarmonicSettings.MIN_DB).toFloat().coerceIn(0f, 1f)
            val x = i * slot + (slot - bar) / 2
            val height = (level * bottom).coerceAtLeast(2f * density)
            val strong = order == selected || order in profile.overrides
            drawRoundRect(
                colors.ink.copy(alpha = 0.10f),
                topLeft = Offset(x, 0f),
                size = Size(bar, bottom),
                cornerRadius = CornerRadius(bar / 3),
            )
            drawRoundRect(
                when {
                    order == selected -> colors.accent
                    strong -> colors.ink.copy(alpha = 0.85f)
                    order % 2 == 0 -> colors.ink.copy(alpha = 0.55f)
                    else -> colors.ink.copy(alpha = 0.38f)
                },
                topLeft = Offset(x, bottom - height),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 3),
            )
            val text = measurer.measure(order.toString(), labelStyle.copy(fontWeight = if (order == selected) FontWeight.Bold else FontWeight.Normal))
            drawText(text, topLeft = Offset(i * slot + (slot - text.size.width) / 2, bottom + 3f * density))
        }
    }
}

private fun balanceText(balance: Double): String = when {
    balance > 0.005 -> "짝수 쪽 ${(balance * 100).roundToInt()} %"
    balance < -0.005 -> "홀수 쪽 ${(-balance * 100).roundToInt()} %"
    else -> "균형"
}

private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", value + 0.0)
