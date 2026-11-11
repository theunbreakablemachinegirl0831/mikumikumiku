package mmm.app.ui.components.glass

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import mmm.app.ui.theme.DefaultTint
import mmm.app.ui.theme.GlassTheme
import kotlin.math.ceil

/**
 * What floating glass can see through: the screen content, kept as a drawing the glass can draw
 * again, blurred, under itself.
 *
 * This is genuine backdrop blur, not a blur of the glass's own content. The content is recorded
 * once per frame into a graphics layer - a display list on the GPU, not a bitmap - and each pane
 * replays the part of it that lies beneath, through a blur. Android only gained a hardware blur
 * for display lists in 12, so on Android 10 and 11 there is no state, and panes fall back to an
 * opaque tinted body.
 */
@Stable
class GlassBackdropState internal constructor(internal val layer: GraphicsLayer) {
    internal var position by mutableStateOf(Offset.Zero)
}

/** The backdrop floating panes blur, or null where they cannot. */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/** The colour the app's backdrop is washed with, for panes that take their colour from it. */
val LocalBackdropTint = compositionLocalOf { DefaultTint }

/** Whether this device can blur what is behind a pane at all. */
val backdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * @param enabled the listener's setting; false gives the fallback material everywhere
 * @return the state to hand to [glassBackdropSource] and to provide as [LocalGlassBackdrop], or
 *   null when blur is off or unsupported
 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean): GlassBackdropState? {
    if (!enabled || !backdropBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * Marks the receiver as what floating glass sees through. Panes that use it must be drawn
 * outside it - beside it, not inside it - or they would be looking at themselves.
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.position = it.positionInRoot() }
        .drawWithContent {
            state.layer.record { this@drawWithContent.drawContent() }
            drawLayer(state.layer)
        }
}

/**
 * Draws the part of [state]'s content that lies under the receiver, blurred, before the
 * receiver's own content. Call after clipping to the pane's shape.
 */
@Composable
internal fun Modifier.glassBackdropBlur(state: GlassBackdropState?, radius: Dp): Modifier {
    if (state == null || radius.value <= 0f) return this
    val blurred = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val effect = remember(radiusPx) { BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
    return this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            // A blur reaches past its own edge for colour. The layer is recorded larger than the
            // pane by that reach, so the pane's edges are blurred from what is really beside
            // them rather than from a smear of their outermost pixels.
            val margin = ceil(radiusPx * 2).toInt()
            val offset = position - state.position
            blurred.renderEffect = effect
            blurred.record(size = IntSize(size.width.toInt() + 2 * margin, size.height.toInt() + 2 * margin)) {
                translate(margin - offset.x, margin - offset.y) { drawLayer(state.layer) }
            }
            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(blurred) }
            drawContent()
        }
}

/**
 * The wash behind everything: the tint over the theme's neutral, with two soft glows. It changes
 * colour slowly when the track changes, and that slow change under still panes is a large part of
 * what makes them read as glass.
 *
 * On dark it runs from the tint down to near-black. On light it is the neutral with the tint
 * breathed over the top - a light theme that took the tint at full strength would stop being light.
 */
@Composable
fun Backdrop(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = GlassTheme.colors
    val animated by animateColorAsState(tint, animationSpec = tween(900), label = "backdrop")
    val top: Color
    val bottom: Color
    val glow: Color
    val glow2: Color
    if (colors.isDark) {
        top = lerp(animated, Color.Black, 0.35f)
        bottom = lerp(animated, colors.canvas, 0.88f)
        glow = lerp(animated, Color.White, 0.25f).copy(alpha = 0.35f)
        glow2 = lerp(animated, Color(0xFF3050FF), 0.4f).copy(alpha = 0.22f)
    } else {
        top = lerp(animated, Color.White, 0.62f)
        bottom = lerp(animated, colors.canvas, 0.9f)
        glow = Color.White.copy(alpha = 0.7f)
        glow2 = lerp(animated, Color(0xFF9DB8FF), 0.5f).copy(alpha = 0.28f)
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.12f), radius = size.width * 0.9f),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.15f, size.height * 0.12f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(glow2, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.6f), radius = size.width * 0.8f),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.95f, size.height * 0.6f),
                )
            },
    ) {
        CompositionLocalProvider(LocalBackdropTint provides tint) { content() }
    }
}
