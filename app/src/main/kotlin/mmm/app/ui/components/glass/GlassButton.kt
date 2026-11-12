package mmm.app.ui.components.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassMotion
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme

/** How much a button asks to be pressed. */
enum class GlassButtonStyle {
    /** The one thing to do here: a solid capsule in the accent. */
    Primary,
    /** An ordinary action: a floating-grade pane. */
    Glass,
    /** A lesser action: a subtle pane. */
    Quiet,
    /** Barely a button: text alone. */
    Text,
}

/**
 * Every button in the app: a capsule that gives a little under the finger and springs back.
 * The give is a scale on the graphics layer, so pressing costs no layout.
 *
 * @param container overrides the body's colour, for buttons that carry a meaning (right, wrong)
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.Glass,
    enabled: Boolean = true,
    container: Color? = null,
    contentColor: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = GlassTheme.reducedMotion
    val scale by animateFloatAsState(
        if (pressed && !reduced) GlassMotion.PRESSED_SCALE else 1f,
        GlassMotion.press(),
        label = "press",
    )
    val solid = container ?: if (style == GlassButtonStyle.Primary) colors.accent else null
    val ink = when {
        !enabled -> colors.inkFaint
        contentColor != null -> contentColor
        container != null -> colors.onAccent
        style == GlassButtonStyle.Primary -> colors.onAccent
        else -> colors.ink
    }
    val body = when {
        solid != null -> Modifier.background(if (enabled) solid else colors.inkFaint.copy(alpha = 0.12f), GlassShapes.pill)
        style == GlassButtonStyle.Glass -> Modifier.glass(GlassShapes.pill, level = GlassLevel.Regular)
        style == GlassButtonStyle.Quiet -> Modifier.glass(GlassShapes.pill)
        else -> Modifier
    }
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(body)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = GlassDimensions.touchTarget)
            .padding(horizontal = if (style == GlassButtonStyle.Text) 12.dp else 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink,
            LocalTextStyle provides TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ink),
        ) { content() }
    }
}

/** A button that is just a word. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        style = if (primary) GlassButtonStyle.Primary else GlassButtonStyle.Glass,
        enabled = enabled,
    ) { Text(text, maxLines = 1) }
}

// The four below are called like Material's buttons, so screens written against those change
// their import and nothing else.

/** The main action. A [colors] container, when given, replaces the accent - right and wrong answers use it. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        style = GlassButtonStyle.Primary,
        enabled = enabled,
        container = colors?.containerColor,
        contentColor = colors?.contentColor,
        content = content,
    )
}

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    GlassButton(onClick, modifier, GlassButtonStyle.Glass, enabled, content = content)
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    GlassButton(onClick, modifier, GlassButtonStyle.Quiet, enabled, content = content)
}

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    GlassButton(onClick, modifier, GlassButtonStyle.Text, enabled, content = content)
}

/**
 * A round button holding an icon. Glass by default; [level] null leaves it bare, for icons that
 * sit in a row of their own kind.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    level: GlassLevel? = GlassLevel.Regular,
    size: Dp = GlassDimensions.touchTarget,
    content: @Composable () -> Unit,
) {
    val colors = GlassTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = GlassTheme.reducedMotion
    val scale by animateFloatAsState(if (pressed && !reduced) 0.9f else 1f, GlassMotion.press(), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (level != null) Modifier.glass(GlassShapes.circle, level = level) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.ink else colors.inkFaint) { content() }
    }
}

/** A round chip that can be selected: the reference's numbered band buttons. */
@Composable
fun RoundChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = GlassTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else Color.Transparent, tween(200), label = "round-chip")
    Box(
        modifier
            .size(size)
            .glass(GlassShapes.circle)
            .background(background, GlassShapes.circle)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides when {
                selected -> colors.onAccent
                dimmed -> colors.inkFaint
                else -> colors.ink
            },
        ) { content() }
    }
}
