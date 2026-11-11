package mmm.app.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mmm.app.data.PlaybackEq
import mmm.app.ui.theme.FilterChip
import mmm.app.ui.theme.GlassButton
import mmm.app.ui.theme.PillTabs
import mmm.app.ui.theme.glass
import mmm.dsp.artifacts.TubeBand
import mmm.eq.EqProfile
import mmm.eq.GraphicEq
import kotlin.math.roundToInt

/** The graphic EQ: a row of faders, ten octave bands or thirty-one third-octave ones. */
@Composable
internal fun GeqSection(state: PlaybackEq, update: ((PlaybackEq) -> PlaybackEq) -> Unit) {
    val layout = state.geq
    Column(Modifier.fillMaxWidth().glass().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PillTabs(
                labels = listOf("10밴드", "31밴드"),
                selected = if (layout.size == GraphicEq.THIRD_OCTAVE.size) 1 else 0,
                onSelect = { choice ->
                    val next = if (choice == 1) GraphicEq.THIRD_OCTAVE else GraphicEq.OCTAVE
                    if (next.size != layout.size) {
                        // The shape carries over: the new faders start where the old curve passes.
                        val shape = EqProfile(layout.filters(state.geqGains), 0.0)
                        val gains = next.centres.map { (shape.magnitudeDbAt(it) * 2).roundToInt() / 2.0 }
                        update { it.copy(geqGains = gains) }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            GlassButton("0으로", onClick = { update { it.copy(geqGains = List(it.geqGains.size) { 0.0 }) } })
        }
        // Ten faders share the width; thirty-one keep a finger's width each and scroll.
        val scrolls = layout.size > GraphicEq.OCTAVE.size
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            layout.centres.forEachIndexed { index, centre ->
                val gain = state.geqGains.getOrElse(index) { 0.0 }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (scrolls) Modifier.width(36.dp) else Modifier.weight(1f),
                ) {
                    Text(
                        if (gain == 0.0) "0" else signed(gain).removeSuffix(".0"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Fader(
                        value = gain,
                        onChange = { v -> update { it.copy(geqGains = it.geqGains.mapIndexed { i, g -> if (i == index) v else g }) } },
                    )
                    Text(shortHz(centre), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Text(
            "곡선이 각 밴드에서 슬라이더가 가리키는 값을 지나도록 필터를 맞춘다. 두 번 누르면 0으로 돌아간다.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/**
 * A vertical fader, ±[FADER_RANGE_DB] in half-decibel steps. Only vertical drags move it, so a
 * sideways swipe across the faders still scrolls the row.
 */
@Composable
private fun Fader(value: Double, onChange: (Double) -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .width(34.dp)
            .height(170.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { change(faderValue(it.y, size.height.toFloat())) },
                    onDoubleTap = { change(0.0) },
                )
            }
            .pointerInput(Unit) {
                var dragged = 0.0
                detectVerticalDragGestures(
                    onDragStart = { dragged = current },
                    onVerticalDrag = { input, amount ->
                        input.consume()
                        dragged = (dragged - amount / size.height * 2 * FADER_RANGE_DB).coerceIn(-FADER_RANGE_DB, FADER_RANGE_DB)
                        val snapped = (dragged * 2).roundToInt() / 2.0
                        if (snapped != current) change(snapped)
                    },
                )
            },
    ) {
        val x = size.width / 2
        val middle = size.height / 2
        val y = middle - (value / FADER_RANGE_DB).toFloat() * middle
        val track = 4f * density
        drawRoundRect(
            Color.White.copy(alpha = 0.16f),
            topLeft = Offset(x - track / 2, 0f),
            size = Size(track, size.height),
            cornerRadius = CornerRadius(track / 2),
        )
        drawLine(Color.White.copy(alpha = 0.3f), Offset(x - 9f * density, middle), Offset(x + 9f * density, middle), 1f * density)
        drawRoundRect(
            Color.White,
            topLeft = Offset(x - track / 2, minOf(y, middle)),
            size = Size(track, kotlin.math.abs(y - middle)),
            cornerRadius = CornerRadius(track / 2),
        )
        drawCircle(Color.White, 9f * density, Offset(x, y.coerceIn(9f * density, size.height - 9f * density)))
    }
}

private const val FADER_RANGE_DB = 12.0

private fun faderValue(y: Float, height: Float): Double =
    (((0.5 - y / height) * 2 * FADER_RANGE_DB).coerceIn(-FADER_RANGE_DB, FADER_RANGE_DB) * 2).roundToInt() / 2.0

private fun shortHz(frequency: Double): String = when {
    frequency >= 1000 -> {
        val k = frequency / 1000
        (if (k == kotlin.math.floor(k)) k.toInt().toString() else ((k * 100).roundToInt() / 100.0).toString()) + "k"
    }
    frequency == kotlin.math.floor(frequency) -> frequency.toInt().toString()
    else -> frequency.toString()
}

/** The valve harmonics: for bass, midrange and treble, how much second and how much third to grow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TubeSection(state: PlaybackEq, update: ((PlaybackEq) -> PlaybackEq) -> Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "진공관 앰프의 음색은 어떤 배음을 얼마나 더하느냐에서 온다. 대역마다 2차(따뜻함)와 3차(선명함) 배음을 " +
                "풀스케일 음 기준 몇 dB로 만들지 정한다. 작은 소리에서는 진공관처럼 더 적게 생긴다.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TUBE_PRESETS.forEach { (name, levels) ->
                FilterChip(
                    selected = state.tube.map { it.secondDb to it.thirdDb } == levels,
                    onClick = {
                        update { s ->
                            s.copy(
                                tubeEnabled = true,
                                tube = s.tube.mapIndexed { i, band -> band.copy(secondDb = levels[i].first, thirdDb = levels[i].second) },
                            )
                        }
                    },
                    label = { Text(name) },
                )
            }
        }
    }
    state.tube.forEachIndexed { index, band ->
        Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                "${TUBE_NAMES.getOrElse(index) { "대역" }} · ${hz(band.fromHz)}–${hz(band.toHz)}",
                style = MaterialTheme.typography.titleMedium,
            )
            HarmonicSlider("2차 배음", band.secondDb) { v ->
                update { s -> s.copy(tube = s.tube.mapIndexed { i, b -> if (i == index) b.copy(secondDb = v) else b }) }
            }
            HarmonicSlider("3차 배음", band.thirdDb) { v ->
                update { s -> s.copy(tube = s.tube.mapIndexed { i, b -> if (i == index) b.copy(thirdDb = v) else b }) }
            }
        }
    }
    Text(
        "배음은 이 앱의 플레이어에서만 만들어진다. 다른 앱의 소리에는 안드로이드가 밴드 EQ만 허용한다. " +
            "고역 대역은 접힘 잡음(aliasing)을 막으려고 샘플레이트의 1/6(44.1 kHz에서 약 7.3 kHz)까지만 쓴다.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun HarmonicSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    LabelledSlider(
        label = label,
        value = if (value <= TubeBand.OFF_DB) "꺼짐" else "${value.roundToInt()} dB",
        position = value.toFloat(),
        range = TubeBand.OFF_DB.toFloat()..TubeBand.MAX_DB.toFloat(),
        onChange = { onChange(it.roundToInt().toDouble()) },
    )
}

private val TUBE_NAMES = listOf("저역", "중역", "고역")

private const val OFF = TubeBand.OFF_DB

/** Second and third harmonic for bass, midrange and treble. */
private val TUBE_PRESETS: List<Pair<String, List<Pair<Double, Double>>>> = listOf(
    "끔" to listOf(OFF to OFF, OFF to OFF, OFF to OFF),
    "싱글엔디드 3극관" to listOf(-34.0 to -58.0, -40.0 to -62.0, -46.0 to -66.0),
    "푸시풀" to listOf(-62.0 to -44.0, -66.0 to -48.0, -70.0 to -52.0),
    "진하게" to listOf(-24.0 to -40.0, -30.0 to -44.0, -36.0 to -50.0),
)
