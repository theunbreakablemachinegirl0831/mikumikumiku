package mmm.app.ui.components.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mmm.app.ui.theme.GlassMotion
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme

/**
 * A row of pills with one drop of accent that flows to the selected one.
 *
 * The drop is what makes it read as liquid: its leading edge moves on a stiff spring and its
 * trailing edge on a soft one, so it stretches out towards where it is going and then gathers
 * itself there, instead of sliding across as a rigid block. With reduced motion both edges move
 * together and at once.
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
    val colors = GlassTheme.colors
    val reduced = GlassTheme.reducedMotion
    val edges = remember(labels.size) { mutableStateListOf<Pair<Float, Float>?>().apply { repeat(labels.size) { add(null) } } }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = edges.getOrNull(selected)

    LaunchedEffect(target) {
        val (toLeft, toRight) = target ?: return@LaunchedEffect
        if (!placed || reduced) {
            left.snapTo(toLeft)
            right.snapTo(toRight)
            placed = true
            return@LaunchedEffect
        }
        val forward = toLeft > left.value
        launch { left.animateTo(toLeft, spring(GlassMotion.DROP_DAMPING, if (forward) GlassMotion.DROP_TRAILING else GlassMotion.DROP_LEADING)) }
        launch { right.animateTo(toRight, spring(GlassMotion.DROP_DAMPING, if (forward) GlassMotion.DROP_LEADING else GlassMotion.DROP_TRAILING)) }
    }

    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
            .padding(contentPadding)
            .drawBehind {
                if (placed) {
                    drawRoundRect(
                        colors.accent,
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
            val color by animateColorAsState(if (index == selected) colors.onAccent else colors.ink, tween(220), label = "pill-text")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .onGloballyPositioned { coordinates ->
                        val x = coordinates.positionInParent().x
                        val edge = x to x + coordinates.size.width
                        if (index < edges.size && edges[index] != edge) edges[index] = edge
                    }
                    // The selected pill is the drop itself; a pane over it would only grey it.
                    .then(if (index == selected) Modifier else Modifier.glass(GlassShapes.pill))
                    .semantics { this.selected = index == selected }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A pill that is on or off: the accent when selected, a subtle pane otherwise. Called like Material's FilterChip. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else Color.Transparent, tween(220), label = "chip")
    val content = if (selected) colors.onAccent else colors.ink
    Box(
        modifier
            .then(if (selected) Modifier else Modifier.glass(GlassShapes.pill))
            .background(background, GlassShapes.pill)
            .semantics { this.selected = selected }
            .clickable(role = Role.Checkbox, onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides content,
            LocalTextStyle provides TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = content),
        ) { label() }
    }
}

/** Material's switch in the theme's ink: the thumb is the accent, the track a faint trace of it. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GlassTheme.colors
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.ink.copy(alpha = 0.8f),
            uncheckedTrackColor = colors.inkFaint.copy(alpha = 0.16f),
            uncheckedBorderColor = colors.inkFaint.copy(alpha = 0.3f),
        ),
    )
}

/** The colours every slider in the app uses: the ink on a faint track, no tick marks. */
@Composable
fun glassSliderColors(): SliderColors {
    val colors = GlassTheme.colors
    return SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.inkFaint.copy(alpha = 0.24f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = colors.inkFaint,
        disabledActiveTrackColor = colors.inkFaint,
        disabledInactiveTrackColor = colors.inkFaint.copy(alpha = 0.12f),
    )
}

/** Material's slider - its touch handling and accessibility are worth keeping - in the theme's ink. */
@Composable
fun GlassSlider(
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
        colors = glassSliderColors(),
    )
}

/** A thin, round-ended progress line in the ink. */
@Composable
fun GlassProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    val colors = GlassTheme.colors
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = colors.accent,
        trackColor = colors.inkFaint.copy(alpha = 0.2f),
        strokeCap = StrokeCap.Round,
    )
}

/**
 * A text field: a translucent container whose rim brightens to the ink when focused. Material's
 * field underneath, so the keyboard, selection handles and accessibility are the platform's own.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    shape: Shape = if (singleLine) GlassShapes.pill else GlassShapes.card,
) {
    val colors = GlassTheme.colors
    val container = colors.subtle.fill
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            disabledTextColor = colors.inkFaint,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            cursorColor = colors.ink,
            focusedBorderColor = colors.ink,
            unfocusedBorderColor = colors.inkFaint.copy(alpha = 0.3f),
            disabledBorderColor = colors.inkFaint.copy(alpha = 0.15f),
            focusedLabelColor = colors.ink,
            unfocusedLabelColor = colors.inkMuted,
            focusedPlaceholderColor = colors.inkMuted,
            unfocusedPlaceholderColor = colors.inkMuted,
        ),
    )
}
