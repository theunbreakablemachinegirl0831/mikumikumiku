package mmm.app.ui.components.glass

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme

/**
 * Blurs what is behind the dialog or sheet this is called from, on devices that can.
 *
 * Dialogs and sheets are windows of their own, so the app cannot blur its content under them the
 * way it does under the bottom panel; the system can, since Android 12, when the device has
 * cross-window blur switched on. Where it does not, the request is ignored and the scrim alone
 * separates the layers.
 */
@Composable
internal fun BlurBehindWindow(radiusDp: Int = 22) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (radiusDp * view.resources.displayMetrics.density).toInt() }
        }
        onDispose { }
    }
}

/**
 * A dialog: a prominent pane floating over a dimmed, blurred screen, asking one thing.
 *
 * @param confirm the action's label; [danger] colours it for actions that cannot be undone
 */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismiss: String = "취소",
    danger: Boolean = false,
) {
    val colors = GlassTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        BlurBehindWindow()
        // The app's backdrop belongs to the app's window; a pane in this one must not reach for it.
        CompositionLocalProvider(LocalContentColor provides colors.ink, LocalGlassBackdrop provides null) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    // A dialog's window is its own, with nothing of the app behind the pane to
                    // show through, so it takes the solid body in the backdrop's colour.
                    .glass(GlassShapes.floating, level = GlassLevel.Prominent, floating = true)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(onClick = onDismiss, modifier = Modifier.weight(1f), style = GlassButtonStyle.Quiet) { Text(dismiss) }
                    GlassButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        style = GlassButtonStyle.Primary,
                        container = if (danger) colors.danger else null,
                    ) { Text(confirm) }
                }
            }
        }
    }
}
