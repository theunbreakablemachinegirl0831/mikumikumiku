package mmm.app.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Which of the two themes to use. */
enum class ThemeMode(val label: String) {
    SYSTEM("시스템"),
    LIGHT("라이트"),
    DARK("다크"),
}

/** Backdrop colour when nothing is playing: a dusky violet, like the reference's library. */
val DefaultTint = Color(0xFF5B4B7A)

/** The design system's tokens, for the composable asking: `GlassTheme.colors.ink`. */
object GlassTheme {
    val colors: GlassColors
        @Composable @ReadOnlyComposable get() = LocalGlassColors.current

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * The app's theme: the glass tokens, Material's scheme derived from them, the type scale, and the
 * ink as the default content colour - Material only sets that inside its own surfaces, and most
 * of this app is drawn straight onto the backdrop.
 */
@Composable
fun MmmTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkGlass else LightGlass
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // The system bars' icons follow the theme in use, which may not be the system's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = materialScheme(colors), typography = glassTypography(colors)) {
        CompositionLocalProvider(
            LocalGlassColors provides colors,
            LocalReducedMotion provides reducedMotion,
            LocalContentColor provides colors.ink,
            content = content,
        )
    }
}
