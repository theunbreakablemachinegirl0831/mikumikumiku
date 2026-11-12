package mmm.app.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's motion, as a handful of springs. Springs rather than durations because a spring
 * carries on from whatever speed a finger left it at, and that continuity is most of what makes
 * an interface feel physical.
 */
object GlassMotion {
    /** A control giving under the finger and coming back. */
    const val PRESS_DAMPING = 0.55f
    const val PRESS_STIFFNESS = 520f
    const val PRESSED_SCALE = 0.95f

    /** The leading and trailing edges of a selection drop: unequal, so it stretches as it moves. */
    const val DROP_DAMPING = 0.78f
    const val DROP_LEADING = 620f
    const val DROP_TRAILING = 240f

    /** Panels and sheets arriving. */
    const val PANEL_DAMPING = 0.85f
    const val PANEL_STIFFNESS = 300f

    /** Screens changing places. */
    const val PAGE_DAMPING = 0.9f
    const val PAGE_STIFFNESS = 340f

    fun <T> press(): FiniteAnimationSpec<T> = spring(PRESS_DAMPING, PRESS_STIFFNESS)

    fun <T> panel(): FiniteAnimationSpec<T> = spring(PANEL_DAMPING, PANEL_STIFFNESS)

    /** [spec], or an immediate change when the listener has asked the system for less motion. */
    fun <T> unlessReduced(reduced: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduced) snap() else spec
}

/**
 * True when the system's animations are switched off. Compose already runs its own animations
 * at the system's animation scale; this is for the purely decorative ones - a control shrinking
 * under the finger, the artwork breathing - which are then not started at all.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
