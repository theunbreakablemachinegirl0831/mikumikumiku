package mmm.app.ui.visual

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mmm.app.MmmApplication
import mmm.app.data.TrainerSettings
import mmm.app.data.VisualizerColor
import mmm.app.data.VisualizerScaling
import mmm.app.data.VisualizerWeighting
import mmm.app.ui.components.ColorPicker
import mmm.app.ui.components.SwatchRow
import mmm.app.ui.components.glass.FilterChip
import mmm.app.ui.components.glass.GlassActionSheet
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.PillTabs
import mmm.app.ui.components.glass.Switch
import mmm.app.ui.theme.GlassTheme
import kotlin.math.roundToInt

/**
 * Everything about how the spectrum is drawn, in one sheet: its colour, how its height is
 * scaled, and the frequency weighting it is shown through. Opened from the player's ⋮ menu and
 * from Settings; every change shows at once on the spectrum behind the sheet and is saved as it
 * is made.
 */
@Composable
fun VisualizerSheet(app: MmmApplication, onDismiss: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    GlassActionSheet(
        onDismiss = onDismiss,
        title = "비주얼라이저",
        actions = {
            GlassButton("기본값으로", onClick = {
                app.settings.resetVisualizerColor()
                app.settings.setVisualizerScaling(VisualizerScaling.AUTO)
                app.settings.setVisualizerWeighting(VisualizerWeighting.FLAT)
                app.settings.setVisualizerTilt(true)
            })
            GlassButton("닫기", onClick = onDismiss, primary = true)
        },
    ) {
        ColorSection(app, settings)
        HorizontalDivider(color = GlassTheme.colors.inkFaint.copy(alpha = 0.3f))
        ScalingSection(app, settings)
        HorizontalDivider(color = GlassTheme.colors.inkFaint.copy(alpha = 0.3f))
        WeightingSection(app, settings)
    }
}

@Composable
private fun ColorSection(app: MmmApplication, settings: TrainerSettings) {
    Label("색")
    val presets = VisualizerColor.entries
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        SwatchRow(
            swatches = presets.map { swatchColor(it, settings) },
            selected = presets.indexOf(settings.visualizerColor),
            onSelect = { app.settings.setVisualizerColor(presets[it]) },
            labels = presets.map { it.label },
        )
    }
    Text(settings.visualizerColor.label, style = MaterialTheme.typography.labelMedium, color = GlassTheme.colors.inkMuted)
    when (settings.visualizerColor) {
        VisualizerColor.CUSTOM -> ColorPicker(
            color = Color(settings.visualizerCustom),
            onChange = { app.settings.setVisualizerCustom(it.argb()) },
        )
        VisualizerColor.GRADIENT -> {
            var end by rememberSaveable { mutableIntStateOf(0) }
            PillTabs(labels = listOf("저역 쪽 색", "고역 쪽 색"), selected = end, onSelect = { end = it }, fill = true)
            val start = Color(settings.visualizerGradientStart)
            val finish = Color(settings.visualizerGradientEnd)
            ColorPicker(
                color = if (end == 0) start else finish,
                onChange = {
                    if (end == 0) {
                        app.settings.setVisualizerGradient(it.argb(), settings.visualizerGradientEnd)
                    } else {
                        app.settings.setVisualizerGradient(settings.visualizerGradientStart, it.argb())
                    }
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun ScalingSection(app: MmmApplication, settings: TrainerSettings) {
    Label("높이 맞춤")
    PillTabs(
        labels = VisualizerScaling.entries.map { it.label },
        selected = VisualizerScaling.entries.indexOf(settings.visualizerScaling),
        onSelect = { app.settings.setVisualizerScaling(VisualizerScaling.entries[it]) },
        fill = true,
    )
    if (settings.visualizerScaling == VisualizerScaling.AUTO) {
        Note("가장 큰 부분이 늘 화면 안에 들어오도록 위쪽 끝이 음악을 따라간다. 커질 때는 바로, 작아질 때는 초당 6 dB씩 천천히 내려온다.")
    } else {
        Note("위쪽 끝과 표시 범위를 직접 정한다. 위쪽 끝을 넘는 부분도 잘리지 않고 그 위로 그려진다.")
    }
    ValueSlider(
        label = "표시 범위",
        value = settings.visualizerRangeDb,
        range = TrainerSettings.RANGE_DB_RANGE,
        unit = "dB",
        onChange = { app.settings.setVisualizerWindow(settings.visualizerTopDb, it) },
    )
    if (settings.visualizerScaling == VisualizerScaling.MANUAL) {
        ValueSlider(
            label = "위쪽 끝",
            value = settings.visualizerTopDb,
            range = TrainerSettings.TOP_DB_RANGE,
            unit = "dBFS",
            onChange = { app.settings.setVisualizerWindow(it, settings.visualizerRangeDb) },
        )
    }
    SwitchRow(
        title = "고역 기울기 +3 dB/oct",
        subtitle = "음악의 에너지는 고역으로 갈수록 준다. 표시에만 기울기를 더해 고역까지 고르게 움직이게 한다.",
        checked = settings.visualizerTilt,
        onChange = app.settings::setVisualizerTilt,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeightingSection(app: MmmApplication, settings: TrainerSettings) {
    Label("주파수 가중")
    PillTabs(
        labels = VisualizerWeighting.entries.map { it.label },
        selected = VisualizerWeighting.entries.indexOf(settings.visualizerWeighting),
        onSelect = { app.settings.setVisualizerWeighting(VisualizerWeighting.entries[it]) },
    )
    when (settings.visualizerWeighting) {
        VisualizerWeighting.FLAT -> Note("측정한 그대로 보여 준다.")
        VisualizerWeighting.A -> Note("IEC 61672의 A 특성: 1 kHz에서 0 dB, 귀가 둔한 저역과 초고역을 낮춰 보여 준다.")
        VisualizerWeighting.EQUAL_LOUDNESS -> {
            Note("ISO 226:2003 등청감 곡선: 선택한 크기(phon)에서 1 kHz와 같은 크기로 들리려면 각 주파수가 얼마나 더 커야 하는지만큼 낮춰 보여 준다.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainerSettings.PHON_LEVELS.forEach { phon ->
                    FilterChip(
                        selected = settings.visualizerPhon == phon,
                        onClick = { app.settings.setVisualizerPhon(phon) },
                        label = { Text("$phon phon") },
                    )
                }
            }
        }
    }
    if (settings.visualizerWeighting != VisualizerWeighting.FLAT) {
        SwitchRow(
            title = "가중한 스펙트럼",
            subtitle = "끄면 가중하지 않은 스펙트럼만 그린다.",
            checked = settings.visualizerShowWeighted,
            onChange = app.settings::setVisualizerShowWeighted,
        )
        SwitchRow(
            title = "원래 스펙트럼 겹쳐 보기",
            subtitle = "가중하지 않은 스펙트럼을 점선으로 겹쳐 그려 둘을 비교한다.",
            checked = settings.visualizerShowRaw,
            onChange = app.settings::setVisualizerShowRaw,
        )
    }
    Note("가중은 그림에만 적용된다. 들리는 소리와 EQ는 바뀌지 않는다.")
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GlassTheme.colors.inkMuted)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GlassTheme.colors.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, unit: String, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} $unit", style = MaterialTheme.typography.bodyMedium, color = GlassTheme.colors.inkMuted)
        }
        GlassSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL
