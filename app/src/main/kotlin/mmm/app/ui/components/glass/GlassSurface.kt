package mmm.app.ui.components.glass

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import mmm.app.ui.theme.GlassColors
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassMaterial
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme

/**
 * How much glass a surface is.
 *
 * - [Subtle]: lies in the content - cards, chips, secondary controls. A light body, a fine rim,
 *   no shadow, nothing blurred.
 * - [Regular]: floats over the content - the navigation panel, floating buttons. A brighter rim,
 *   a soft halo, and the content behind it blurred where the device can.
 * - [Prominent]: demands attention - dialogs, sheets. The most body and depth, the widest blur.
 */
enum class GlassLevel { Subtle, Regular, Prominent }

internal fun GlassColors.material(level: GlassLevel): GlassMaterial = when (level) {
    GlassLevel.Subtle -> subtle
    GlassLevel.Regular -> regular
    GlassLevel.Prominent -> prominent
}

/**
 * Makes the receiver a pane of glass.
 *
 * A pane is built from the outside in, the way the real thing catches light: a halo on what is
 * under it (floating grades only), then its body, a sheen where light gathers along the top, and
 * a rim that is bright where it faces the light - top and leading edge - and nearly gone where it
 * faces away. Nothing here is a blur of the pane's own content; when something behind is blurred
 * it is the real backdrop (see [glassBackdropSource]), and when that is not possible the pane is
 * simply more opaque and says so in its colour rather than faking it.
 *
 * @param tint colours the body, for warnings and highlights
 * @param floating the pane sits over content that moves beneath it, so it should blur that
 *   content, or - where it cannot - be opaque enough to be read on
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    tint: Color? = null,
    level: GlassLevel = GlassLevel.Subtle,
    floating: Boolean = false,
): Modifier {
    val colors = GlassTheme.colors
    val material = colors.material(level)
    val backdrop = if (floating) LocalGlassBackdrop.current else null
    val ambient = LocalBackdropTint.current

    val body = when {
        backdrop != null -> material.veil
        // No blur to be had: take the backdrop's colour and enough opacity to be read on.
        floating -> lerp(ambient, colors.solidBase, if (colors.isDark) 0.5f else 0.82f).copy(alpha = material.solidAlpha)
        else -> material.fill
    }
    val fill = tint?.copy(alpha = TINT_ALPHA)?.compositeOver(body) ?: body

    return this
        // Only what floats casts a halo: a pane lying in the content has nothing under it to fall on.
        // On light every grade casts one (see LightGlass): there it is the shadow that shows the pane at all.
        .then(if (floating || level == GlassLevel.Prominent || !colors.isDark) Modifier.glassShadow(shape, material) else Modifier)
        .clip(shape)
        .glassBackdropBlur(backdrop, material.blurRadius)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val edge = GlassDimensions.edge.toPx()
            val sheen = Brush.verticalGradient(
                0f to material.sheen,
                SHEEN_DEPTH to Color.Transparent,
                startY = 0f,
                endY = size.height,
            )
            // Lit from the top-left: the rim fades along the diagonal, with a faint return at
            // the far corner where a real pane picks the light up again from inside.
            val rim = Brush.linearGradient(
                0f to material.edgeLight,
                0.45f to material.edgeShade,
                0.8f to material.edgeShade,
                1f to lerp(material.edgeShade, material.edgeLight, 0.45f),
                start = Offset.Zero,
                end = Offset(size.width * 0.35f, size.height),
            )
            // A dialog-grade pane also carries a broad reflection in its lit corner.
            val reflection = if (level == GlassLevel.Prominent) {
                Brush.radialGradient(
                    listOf(material.sheen, Color.Transparent),
                    center = Offset(size.width * 0.15f, 0f),
                    radius = size.maxDimension * 0.7f,
                )
            } else {
                null
            }
            onDrawWithContent {
                drawRect(fill)
                drawRect(sheen)
                if (reflection != null) drawRect(reflection)
                drawContent()
                drawOutline(outline, rim, style = Stroke(edge))
            }
        }
}

/**
 * The halo of a floating pane: a soft shadow drawn only outside the pane's outline. Drawn under
 * a translucent pane, an ordinary elevation shadow shows through it as a grey smear; cutting the
 * pane's own shape out of it leaves what a pane of glass would leave.
 */
private fun Modifier.glassShadow(shape: Shape, material: GlassMaterial): Modifier {
    if (material.shadow.alpha == 0f) return this
    return drawWithCache {
        val path = Path().apply {
            when (val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // The shape itself is drawn clear; only its shadow layer shows.
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(material.shadowRadius.toPx(), 0f, material.shadowOffset.toPx(), material.shadow.toArgb())
        }
        onDrawBehind {
            clipPath(path, ClipOp.Difference) {
                drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
            }
        }
    }
}

/** A pane of glass holding [content]. For most call sites the [glass] modifier is enough. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Subtle,
    shape: Shape = GlassShapes.card,
    tint: Color? = null,
    floating: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tint, level, floating), contentAlignment = contentAlignment, content = content)
}

private const val TINT_ALPHA = 0.22f
private const val SHEEN_DEPTH = 0.55f
