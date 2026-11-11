package mmm.app.ui.components.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme

/**
 * A bottom sheet: Material's, for its drag, its back handling and its insets, dressed as a
 * prominent pane - the backdrop's colour in its body, a bright rim, the screen behind it dimmed
 * and, where the device can, blurred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = GlassTheme.colors
    val material = colors.prominent
    val body = lerp(LocalBackdropTint.current, colors.solidBase, if (colors.isDark) 0.62f else 0.86f).copy(alpha = material.solidAlpha)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = GlassShapes.sheet,
        containerColor = body,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).size(width = 38.dp, height = 5.dp).background(colors.inkFaint, GlassShapes.pill))
        },
    ) {
        BlurBehindWindow()
        // The app's backdrop belongs to the app's window; a pane in this one must not reach for it.
        CompositionLocalProvider(LocalGlassBackdrop provides null) { content() }
    }
}

/** A dropdown menu as a small floating pane: rounded, rimmed, in the backdrop's colour. */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val material = colors.prominent
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = GlassShapes.menu,
        containerColor = lerp(LocalBackdropTint.current, colors.solidBase, if (colors.isDark) 0.6f else 0.88f).copy(alpha = material.solidAlpha),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, material.edgeLight.copy(alpha = material.edgeLight.alpha * 0.6f)),
        content = content,
    )
}
