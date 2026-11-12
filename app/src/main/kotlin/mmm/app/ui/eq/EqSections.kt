package mmm.app.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mmm.app.data.PlaybackEq
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.PillTabs
import mmm.app.ui.components.glass.glass
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
    val ink = GlassTheme.colors.ink
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
            ink.copy(alpha = 0.16f),
            topLeft = Offset(x - track / 2, 0f),
            size = Size(track, size.height),
            cornerRadius = CornerRadius(track / 2),
        )
        drawLine(ink.copy(alpha = 0.3f), Offset(x - 9f * density, middle), Offset(x + 9f * density, middle), 1f * density)
        drawRoundRect(
            ink,
            topLeft = Offset(x - track / 2, minOf(y, middle)),
            size = Size(track, kotlin.math.abs(y - middle)),
            cornerRadius = CornerRadius(track / 2),
        )
        drawCircle(ink, 9f * density, Offset(x, y.coerceIn(9f * density, size.height - 9f * density)))
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
