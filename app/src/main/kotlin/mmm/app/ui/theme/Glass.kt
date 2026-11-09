package mmm.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's surfaces: frosted panes over the tinted backdrop.
 *
 * Android cannot refract or blur what is behind a view the way iOS's Liquid Glass does - not
 * portably - so the effect is built from what can be drawn: a translucent fill that lets the
 * backdrop's colour through, a faint top-lit gradient, and a hairline edge that is brighter at the
 * top, the way light catches the rim of a pane.
 */
object Glass {
    val fill: Color = Color.White.copy(alpha = 0.07f)
    val fillStrong: Color = Color.White.copy(alpha = 0.12f)
    val edge: Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))
    val sheen: Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent))
    val shape: Shape = RoundedCornerShape(24.dp)
    val pill: Shape = RoundedCornerShape(percent = 50)
}

/** A frosted pane. [tint] colours the glass for warnings and highlights. */
fun Modifier.glass(
    shape: Shape = Glass.shape,
    tint: Color? = null,
    strong: Boolean = false,
    edgeWidth: Dp = 1.dp,
): Modifier {
    val base = if (strong) Glass.fillStrong else Glass.fill
    val fill = tint?.copy(alpha = 0.22f)?.compositeOver(base) ?: base
    return this
        .clip(shape)
        .background(fill)
        .background(Glass.sheen)
        .border(BorderStroke(edgeWidth, Glass.edge), shape)
}

/**
 * Drop-in for Material's Card, drawn as glass. The [colors] container colour, when given, tints
 * the pane - existing calls that asked for an error or highlight container keep their meaning.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(tint = colors?.containerColor), content = content)
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(tint = colors?.containerColor).clickable(onClick = onClick), content = content)
}
