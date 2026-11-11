package mmm.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What one grade of glass is made of. A pane is drawn from these and nothing else, so the three
 * grades differ only in numbers and stay recognisably the same material.
 */
@Immutable
data class GlassMaterial(
    /** The pane's own body, over whatever is behind it. */
    val fill: Color,
    /** Light gathered at the top of the pane, fading out down its upper half. */
    val sheen: Color,
    /** The rim where it faces the light: top and leading edge. */
    val edgeLight: Color,
    /** The rim where it faces away. */
    val edgeShade: Color,
    /** The halo a floating pane leaves around itself; transparent for panes that lie flat. */
    val shadow: Color,
    val shadowRadius: Dp,
    val shadowOffset: Dp,
    /** How far what is behind is blurred, where the device can blur it. */
    val blurRadius: Dp,
    /** Laid over the blurred backdrop instead of [fill]: the blur already separates it. */
    val veil: Color,
    /**
     * How opaque the pane has to be when it floats over moving content and cannot blur it, for
     * text on it to stay readable. It then takes its colour from the backdrop instead.
     */
    val solidAlpha: Float,
)

/** Every colour the app draws with. Screens ask for a role - ink, accent, a grade of glass - never a value. */
@Immutable
data class GlassColors(
    val isDark: Boolean,
    /** Text and icons. */
    val ink: Color,
    /** Second lines, captions, inactive icons. */
    val inkMuted: Color,
    /** Disabled things, tracks, hairlines. */
    val inkFaint: Color,
    /** The fill of whatever is selected: white on dark, near-black on light. */
    val accent: Color,
    val onAccent: Color,
    /** The one colour allowed to mean something: the playing track, the predicted curve. */
    val signal: Color,
    val danger: Color,
    /** The neutral the backdrop settles to, away from the artwork's colour. */
    val canvas: Color,
    /** What a floating pane's colour leans towards when it cannot blur: black on dark, white on light. */
    val solidBase: Color,
    val scrim: Color,
    val subtle: GlassMaterial,
    val regular: GlassMaterial,
    val prominent: GlassMaterial,
)

internal val DarkGlass = GlassColors(
    isDark = true,
    ink = Color(0xFFF5F5F7),
    inkMuted = Color(0xFFB4B3BF),
    inkFaint = Color(0x52FFFFFF),
    accent = Color(0xFFF5F5F7),
    onAccent = Color(0xFF15151A),
    signal = Color(0xFF8FD9FF),
    danger = Color(0xFFFF8A80),
    canvas = Color(0xFF09090C),
    solidBase = Color.Black,
    scrim = Color(0x8C000000),
    subtle = GlassMaterial(
        fill = Color(0x12FFFFFF),
        sheen = Color(0x0FFFFFFF),
        edgeLight = Color(0x33FFFFFF),
        edgeShade = Color(0x0AFFFFFF),
        shadow = Color.Transparent,
        shadowRadius = 0.dp,
        shadowOffset = 0.dp,
        blurRadius = 0.dp,
        veil = Color(0x12FFFFFF),
        solidAlpha = 0.80f,
    ),
    regular = GlassMaterial(
        fill = Color(0x1FFFFFFF),
        sheen = Color(0x17FFFFFF),
        edgeLight = Color(0x47FFFFFF),
        edgeShade = Color(0x0FFFFFFF),
        shadow = Color(0x40000000),
        shadowRadius = 22.dp,
        shadowOffset = 8.dp,
        blurRadius = 28.dp,
        veil = Color(0x4D14141A),
        solidAlpha = 0.88f,
    ),
    prominent = GlassMaterial(
        fill = Color(0x29FFFFFF),
        sheen = Color(0x1FFFFFFF),
        edgeLight = Color(0x57FFFFFF),
        edgeShade = Color(0x14FFFFFF),
        shadow = Color(0x59000000),
        shadowRadius = 32.dp,
        shadowOffset = 12.dp,
        blurRadius = 40.dp,
        veil = Color(0x6614141A),
        solidAlpha = 0.94f,
    ),
)

// Not the dark theme inverted: on a light ground glass reads by being whiter than what is behind
// it and by the shadow it casts, where on a dark ground it reads by its bright rim.
internal val LightGlass = GlassColors(
    isDark = false,
    ink = Color(0xFF1C1C1E),
    inkMuted = Color(0xFF5E5E66),
    inkFaint = Color(0x3D000000),
    accent = Color(0xFF1C1C1E),
    onAccent = Color(0xFFFFFFFF),
    signal = Color(0xFF0A6FA0),
    danger = Color(0xFFB3261E),
    canvas = Color(0xFFF2F2F7),
    solidBase = Color.White,
    scrim = Color(0x52000000),
    subtle = GlassMaterial(
        fill = Color(0x80FFFFFF),
        sheen = Color(0x59FFFFFF),
        edgeLight = Color(0xE6FFFFFF),
        edgeShade = Color(0x14000000),
        shadow = Color.Transparent,
        shadowRadius = 0.dp,
        shadowOffset = 0.dp,
        blurRadius = 0.dp,
        veil = Color(0x80FFFFFF),
        solidAlpha = 0.84f,
    ),
    regular = GlassMaterial(
        fill = Color(0xA6FFFFFF),
        sheen = Color(0x73FFFFFF),
        edgeLight = Color(0xF2FFFFFF),
        edgeShade = Color(0x1A000000),
        shadow = Color(0x1F1A2030),
        shadowRadius = 24.dp,
        shadowOffset = 8.dp,
        blurRadius = 28.dp,
        veil = Color(0x8CFFFFFF),
        solidAlpha = 0.92f,
    ),
    prominent = GlassMaterial(
        fill = Color(0xCCFFFFFF),
        sheen = Color(0x8CFFFFFF),
        edgeLight = Color(0xFFFFFFFF),
        edgeShade = Color(0x1F000000),
        shadow = Color(0x2E1A2030),
        shadowRadius = 34.dp,
        shadowOffset = 12.dp,
        blurRadius = 40.dp,
        veil = Color(0xB3FFFFFF),
        solidAlpha = 0.96f,
    ),
)

val LocalGlassColors = staticCompositionLocalOf { DarkGlass }

/**
 * Material's scheme, for the Material components still in use (progress rings, radio buttons,
 * text selection) and for the semantic containers screens tint their cards with.
 */
internal fun materialScheme(glass: GlassColors): ColorScheme =
    if (glass.isDark) {
        darkColorScheme(
            primary = glass.accent,
            onPrimary = glass.onAccent,
            primaryContainer = Color(0x33FFFFFF),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFB9B9C6),
            onSecondary = Color(0xFF15151A),
            secondaryContainer = Color(0x29FFFFFF),
            onSecondaryContainer = Color.White,
            tertiary = glass.signal,
            onTertiary = Color(0xFF002233),
            tertiaryContainer = Color(0xFF1E5A73),
            onTertiaryContainer = Color(0xFFD6F2FF),
            error = glass.danger,
            onError = Color(0xFF3A0000),
            errorContainer = Color(0xFF7A2A2A),
            onErrorContainer = Color(0xFFFFDAD6),
            // Transparent, so screens draw on the backdrop rather than over it.
            background = Color.Transparent,
            onBackground = glass.ink,
            surface = Color(0xFF1C1B22),
            onSurface = glass.ink,
            surfaceVariant = Color(0x1FFFFFFF),
            onSurfaceVariant = glass.inkMuted,
            surfaceContainer = Color(0xFF26252D),
            surfaceContainerHigh = Color(0xFF2E2D36),
            surfaceContainerHighest = Color(0xFF37363F),
            surfaceContainerLow = Color(0xFF1F1E25),
            surfaceContainerLowest = Color(0xFF141318),
            outline = Color(0x52FFFFFF),
            outlineVariant = Color(0x24FFFFFF),
        )
    } else {
        lightColorScheme(
            primary = glass.accent,
            onPrimary = glass.onAccent,
            primaryContainer = Color(0x14000000),
            onPrimaryContainer = glass.ink,
            secondary = Color(0xFF5E5E66),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDDE3F0),
            onSecondaryContainer = glass.ink,
            tertiary = glass.signal,
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFBFE6F7),
            onTertiaryContainer = Color(0xFF00344D),
            error = glass.danger,
            onError = Color.White,
            errorContainer = Color(0xFFF7C9C5),
            onErrorContainer = Color(0xFF410E0B),
            background = Color.Transparent,
            onBackground = glass.ink,
            surface = Color(0xFFF7F7FA),
            onSurface = glass.ink,
            surfaceVariant = Color(0x0F000000),
            onSurfaceVariant = glass.inkMuted,
            surfaceContainer = Color(0xFFF2F2F7),
            surfaceContainerHigh = Color(0xFFECECF2),
            surfaceContainerHighest = Color(0xFFE6E6EC),
            surfaceContainerLow = Color(0xFFF7F7FA),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            outline = Color(0x3D000000),
            outlineVariant = Color(0x1F000000),
        )
    }
