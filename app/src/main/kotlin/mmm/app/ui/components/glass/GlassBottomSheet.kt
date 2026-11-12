package mmm.app.ui.components.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
        // Never taller than most of the screen, so the scrim stays visible above it and a sheet
        // with a lot in it scrolls inside itself instead of pushing its top off the screen.
        val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp
        // The app's backdrop belongs to the app's window; a pane in this one must not reach for it.
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) { content() }
        }
    }
}

/**
 * A sheet with a title, a body that scrolls on its own and, pinned under it, the sheet's
 * buttons: however long the body, the actions stay where the thumb is and are never scrolled
 * away or pushed under the navigation bar.
 */
@Composable
fun GlassActionSheet(
    onDismiss: () -> Unit,
    title: String,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassBottomSheet(onDismiss = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
        if (actions != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

private const val SHEET_MAX_FRACTION = 0.85f

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
