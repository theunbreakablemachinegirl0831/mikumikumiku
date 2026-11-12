package mmm.app.ui.components.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassMotion
import mmm.app.ui.theme.GlassTheme

/**
 * Height of the floating bottom panel, for the screens that scroll underneath it: each leaves
 * this much room after its last item, so nothing ends up stuck behind the glass.
 */
val LocalPanelInset = compositionLocalOf { 0.dp }

class GlassNavItem(val label: String, val icon: ImageVector)

/**
 * The row of destinations inside the floating panel: icons only, as in the reference, each
 * announced by its label to screen readers, with a lens of brighter glass under the selected one.
 *
 * The lens moves like a drop: its leading edge goes first and its trailing edge catches up, so
 * between two destinations it stretches across both. The panel itself - its glass, its shape -
 * belongs to whoever places this row, because the panel may hold more than the row.
 */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val reduced = GlassTheme.reducedMotion
    val left = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    val right = remember { Animatable(selected.coerceAtLeast(0) + 1f) }
    LaunchedEffect(selected) {
        if (selected < 0) return@LaunchedEffect
        if (reduced) {
            left.snapTo(selected.toFloat())
            right.snapTo(selected + 1f)
            return@LaunchedEffect
        }
        val forward = selected > left.value
        launch { left.animateTo(selected.toFloat(), spring(LENS_DAMPING, if (forward) GlassMotion.DROP_TRAILING else GlassMotion.DROP_LEADING)) }
        launch { right.animateTo(selected + 1f, spring(LENS_DAMPING, if (forward) GlassMotion.DROP_LEADING else GlassMotion.DROP_TRAILING)) }
    }
    val lens = colors.ink.copy(alpha = if (colors.isDark) 0.16f else 0.09f)
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val cell = size.width / items.size
                val height = size.height * 0.74f
                val inset = (cell - height * 1.5f).coerceAtLeast(0f) / 2
                drawRoundRect(
                    lens,
                    topLeft = Offset(left.value * cell + inset, (size.height - height) / 2),
                    size = Size(((right.value - left.value) * cell - 2 * inset).coerceAtLeast(0f), height),
                    cornerRadius = CornerRadius(height / 2),
                )
            },
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selected
            val color by animateColorAsState(if (active) colors.ink else colors.inkMuted.copy(alpha = 0.7f), tween(220), label = "nav")
            val scale by animateFloatAsState(
                if (active && !reduced) 1.12f else 1f,
                spring(dampingRatio = 0.5f, stiffness = 400f),
                label = "nav-scale",
            )
            Box(
                Modifier
                    .weight(1f)
                    .semantics { this.selected = active }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = color,
                    modifier = Modifier.size(GlassDimensions.navIcon).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }
        }
    }
}

private const val LENS_DAMPING = 0.75f
