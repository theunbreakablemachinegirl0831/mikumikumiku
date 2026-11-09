package mmm.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Default backdrop colour when nothing is playing: a dusky violet, like the reference's library. */
val DefaultTint = Color(0xFF5B4B7A)

private val GlassDark = darkColorScheme(
    // White is the accent, as in the reference: selected chips, sliders and switches are white
    // on glass, and the colour comes from the backdrop instead of the controls.
    primary = Color(0xFFF5F5F7),
    onPrimary = Color(0xFF15151A),
    primaryContainer = Color(0x33FFFFFF),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB9B9C6),
    onSecondary = Color(0xFF15151A),
    secondaryContainer = Color(0x29FFFFFF),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF8FD9FF),
    onTertiary = Color(0xFF002233),
    tertiaryContainer = Color(0xFF1E5A73),
    onTertiaryContainer = Color(0xFFD6F2FF),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF7A2A2A),
    onErrorContainer = Color(0xFFFFDAD6),
    // Transparent, so screens draw on the backdrop rather than over it.
    background = Color.Transparent,
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1B22),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xFFB4B3BF),
    surfaceContainer = Color(0xFF26252D),
    surfaceContainerHigh = Color(0xFF2E2D36),
    surfaceContainerHighest = Color(0xFF37363F),
    surfaceContainerLow = Color(0xFF1F1E25),
    surfaceContainerLowest = Color(0xFF141318),
    outline = Color(0x52FFFFFF),
    outlineVariant = Color(0x24FFFFFF),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = base.bodySmall.copy(color = Color(0xFFB4B3BF)),
    )
}

@Composable
fun MmmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlassDark, typography = AppTypography, content = content)
}

/**
 * The colour wash behind everything: the tint at the top fading to near-black, with two soft
 * glows. It changes colour slowly when the track changes, which is what makes the glass panes
 * over it look like glass.
 */
@Composable
fun Backdrop(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animated by animateColorAsState(tint, animationSpec = tween(900), label = "backdrop")
    val top = lerp(animated, Color.Black, 0.35f)
    val bottom = lerp(animated, Color.Black, 0.86f)
    val glow = lerp(animated, Color.White, 0.25f).copy(alpha = 0.35f)
    val glow2 = lerp(animated, Color(0xFF3050FF), 0.4f).copy(alpha = 0.22f)
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
    ) { content() }
}

/**
 * A screen's title. Top-level screens get a large title, as in the reference's settings; inner
 * screens a back chevron and a smaller one.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.glass(Glass.pill).size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로")
            }
            Box(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (onBack == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 2,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

/** A small grey label over a group of rows, as in the reference's settings ("일반", "출력"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** Rows grouped on one pane, with hairlines between them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(), content = content)
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** One row of a group: a title, an optional explanation, and a switch or a chevron at the end. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * Drop-in for Material's FilterChip, drawn as the reference's pills: white when selected, glass
 * otherwise.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = 0.10f),
        animationSpec = tween(220),
        label = "chip",
    )
    val content = if (selected) Color(0xFF15151A) else Color.White
    Box(
        modifier
            .let { if (selected) it else it.glass(Glass.pill) }
            .background(background, Glass.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides content,
            androidx.compose.material3.LocalTextStyle provides TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = content),
        ) { label() }
    }
}

/** Drop-in for Material's Switch, coloured like the reference: white thumb, grey track. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color.White.copy(alpha = 0.45f),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
            uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}
