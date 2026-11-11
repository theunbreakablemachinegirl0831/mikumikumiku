package mmm.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Height of the floating bottom panel, for the screens that scroll underneath it: each leaves
 * this much room after its last item, so nothing ends up stuck behind the glass.
 */
val LocalPanelInset = compositionLocalOf { 0.dp }

/** Text on a selected, white surface. */
val OnWhite = Color(0xFF15151A)

/**
 * A row of pills with one white drop that flows to the selected one.
 *
 * The drop is what makes it read as liquid: its leading edge moves on a stiff spring and its
 * trailing edge on a soft one, so it stretches out towards where it is going and then gathers
 * itself there, instead of sliding across as a rigid block.
 *
 * @param fill share the width equally, for a few fixed choices; otherwise the row scrolls
 */
@Composable
fun PillTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val edges = remember(labels.size) { mutableStateListOf<Pair<Float, Float>?>().apply { repeat(labels.size) { add(null) } } }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = edges.getOrNull(selected)

    LaunchedEffect(target) {
        val (toLeft, toRight) = target ?: return@LaunchedEffect
        if (!placed) {
            left.snapTo(toLeft)
            right.snapTo(toRight)
            placed = true
            return@LaunchedEffect
        }
        val forward = toLeft > left.value
        launch { left.animateTo(toLeft, spring(DROP_DAMPING, if (forward) DROP_TRAILING else DROP_LEADING)) }
        launch { right.animateTo(toRight, spring(DROP_DAMPING, if (forward) DROP_LEADING else DROP_TRAILING)) }
    }

    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
            .padding(contentPadding)
            .drawBehind {
                if (placed) {
                    drawRoundRect(
                        Color.White,
                        topLeft = Offset(left.value, 0f),
                        size = Size((right.value - left.value).coerceAtLeast(0f), size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val color by animateColorAsState(if (index == selected) OnWhite else Color.White, tween(220), label = "pill-text")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .onGloballyPositioned { coordinates ->
                        val x = coordinates.positionInParent().x
                        val edge = x to x + coordinates.size.width
                        if (edges.getOrNull(index) != edge && index < edges.size) edges[index] = edge
                    }
                    .glass(Glass.pill)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A pill button, as the reference's "끄기" and "닫기": glass normally, white when [primary]. It
 * gives a little under the finger and springs back, which is most of what makes glass feel soft.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "press")
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        primary -> OnWhite
        else -> Color.White
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (primary && enabled) Modifier.background(Color.White, Glass.pill) else Modifier.glass(Glass.pill, strong = true))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/** Material's slider in the reference's colours: a white thumb on a white line over a faint track. */
@Composable
fun WhiteSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = Color.White.copy(alpha = 0.4f),
            disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
        ),
    )
}

/** A round glass button holding [content], sized like the reference's numbered band chips. */
@Composable
fun RoundChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val background by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.10f), tween(200), label = "round-chip")
    Box(
        modifier
            .size(size)
            .glass(Glass.pill)
            .background(background, Glass.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides when {
                selected -> OnWhite
                dimmed -> Color.White.copy(alpha = 0.4f)
                else -> Color.White
            },
        ) { content() }
    }
}

private const val DROP_DAMPING = 0.78f
private const val DROP_LEADING = 620f
private const val DROP_TRAILING = 240f
