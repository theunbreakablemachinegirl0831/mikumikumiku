package mmm.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mmm.app.ui.components.glass.GlassTextField
import mmm.app.ui.theme.GlassTheme
import java.util.Locale
import kotlin.math.roundToInt

/** A colour as hue (0-360), saturation and value (0-1): the three things a picker slides. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    fun toColor(): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

    companion object {
        fun of(color: Color): Hsv {
            val out = FloatArray(3)
            android.graphics.Color.colorToHSV(color.toArgb(), out)
            return Hsv(out[0], out[1], out[2])
        }
    }
}

/** "#RRGGBB" for an opaque colour. */
fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

/** The colour a "#RRGGBB" or "RRGGBB" names, or null. */
fun parseHex(text: String): Color? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}

/**
 * A colour picker: a swatch of the colour, three sliders - hue, saturation, brightness - each
 * drawn as the range it moves through, and the colour as a hex code that can be typed.
 *
 * [onChange] is called while sliding, so whatever shows the colour follows the finger.
 */
@Composable
fun ColorPicker(color: Color, onChange: (Color) -> Unit, modifier: Modifier = Modifier) {
    // Kept here rather than recomputed from the colour, so a hue survives sliding saturation to 0.
    var hsv by remember { mutableStateOf(Hsv.of(color)) }
    LaunchedEffect(color) { if (hsv.toColor().toArgb() != color.toArgb()) hsv = Hsv.of(color) }
    val set = { next: Hsv ->
        hsv = next
        onChange(next.toColor())
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(hsv.toColor(), CircleShape)
                    .border(1.dp, GlassTheme.colors.inkFaint, CircleShape),
            )
            HexField(hsv.toColor()) { set(Hsv.of(it)) }
        }
        GradientSlider(
            label = "색상",
            value = hsv.hue / 360f,
            brush = Brush.horizontalGradient(HUES),
            readout = "${hsv.hue.roundToInt()}°",
            onChange = { set(hsv.copy(hue = it * 360f)) },
        )
        GradientSlider(
            label = "채도",
            value = hsv.saturation,
            brush = Brush.horizontalGradient(listOf(hsv.copy(saturation = 0f).toColor(), hsv.copy(saturation = 1f).toColor())),
            readout = "${(hsv.saturation * 100).roundToInt()}%",
            onChange = { set(hsv.copy(saturation = it)) },
        )
        GradientSlider(
            label = "밝기",
            value = hsv.value,
            brush = Brush.horizontalGradient(listOf(Color.Black, hsv.copy(value = 1f).toColor())),
            readout = "${(hsv.value * 100).roundToInt()}%",
            onChange = { set(hsv.copy(value = it)) },
        )
    }
}

@Composable
private fun HexField(color: Color, onColor: (Color) -> Unit) {
    var text by remember { mutableStateOf(color.hex()) }
    LaunchedEffect(color) { if (parseHex(text)?.toArgb() != color.toArgb()) text = color.hex() }
    GlassTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.take(7)
            parseHex(text)?.let(onColor)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.width(140.dp),
    )
}

/**
 * A slider whose track is the range it chooses from, so the choice is seen rather than read: a
 * rainbow for hue, grey to full colour for saturation, black to the colour for brightness.
 */
@Composable
fun GradientSlider(
    label: String,
    value: Float,
    brush: Brush,
    readout: String,
    onChange: (Float) -> Unit,
    height: Dp = 26.dp,
) {
    val colors = GlassTheme.colors
    val current by rememberUpdatedState(onChange)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = label
                    stateDescription = readout
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        current((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        drag(down.id) { change ->
                            change.consume()
                            current((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                },
        ) {
            val radius = size.height / 2
            drawRoundRect(brush, cornerRadius = CornerRadius(radius))
            val x = (value.coerceIn(0f, 1f) * (size.width - 2 * radius)) + radius
            drawCircle(Color.White, radius * 0.82f, Offset(x, radius))
            drawCircle(Color.Black.copy(alpha = 0.35f), radius * 0.82f, Offset(x, radius), style = Stroke(1.5f * density))
        }
    }
}

/** A row of colour swatches; the selected one ringed. */
@Composable
fun SwatchRow(
    swatches: List<Color>,
    selected: Int,
    onSelect: (Int) -> Unit,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        swatches.forEachIndexed { index, swatch ->
            Box(
                Modifier
                    .size(36.dp)
                    .background(swatch, CircleShape)
                    .border(if (index == selected) 2.dp else 1.dp, if (index == selected) colors.ink else colors.inkFaint, CircleShape)
                    .semantics { contentDescription = labels.getOrElse(index) { "" } }
                    .clickable { onSelect(index) },
            )
        }
    }
}

private val HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Hsv(it, 1f, 1f).toColor() }
