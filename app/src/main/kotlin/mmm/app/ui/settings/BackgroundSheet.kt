package mmm.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mmm.app.MmmApplication
import mmm.app.data.BackgroundMode
import mmm.app.data.GradientSpec
import mmm.app.data.NamedGradient
import mmm.app.ui.components.ColorPicker
import mmm.app.ui.components.glass.Backdrop
import mmm.app.ui.components.glass.FilterChip
import mmm.app.ui.components.glass.GlassActionSheet
import mmm.app.ui.components.glass.GlassButton
import mmm.app.ui.components.glass.GlassSlider
import mmm.app.ui.components.glass.GlassTextField
import mmm.app.ui.components.glass.PillTabs
import mmm.app.ui.components.glass.Switch
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.theme.GlassTheme
import kotlin.math.roundToInt

/**
 * The background: follow the album art, as the app always has, or a gradient of the listener's
 * own - two or three colours, an angle, where each colour sits, and the whole moved along its
 * line. The preview at the top and the app behind the sheet both follow every change.
 *
 * Edits are kept here while the finger moves and saved a moment after it stops, so a drag on a
 * colour slider is one save rather than a hundred.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundSheet(app: MmmApplication, onDismiss: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(settings.backgroundGradient) }
    var target by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(draft) {
        if (draft == settings.backgroundGradient) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        app.settings.setBackgroundGradient(draft)
    }
    val custom = settings.backgroundMode == BackgroundMode.CUSTOM

    GlassActionSheet(
        onDismiss = onDismiss,
        title = "배경",
        actions = {
            GlassButton("기본값으로", onClick = {
                draft = GradientSpec.DEFAULT
                app.settings.setBackgroundGradient(GradientSpec.DEFAULT)
                app.settings.setBackgroundMode(BackgroundMode.ALBUM)
            })
            GlassButton("닫기", onClick = onDismiss, primary = true)
        },
    ) {
        PillTabs(
            labels = BackgroundMode.entries.map { it.label },
            selected = BackgroundMode.entries.indexOf(settings.backgroundMode),
            onSelect = { app.settings.setBackgroundMode(BackgroundMode.entries[it]) },
            fill = true,
        )
        if (!custom) {
            Text(
                "지금 곡의 앨범아트 색이 배경에 은은하게 번진다. 곡이 없을 때는 기본 보라색이다.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.colors.inkMuted,
            )
            return@GlassActionSheet
        }

        // The preview: the same drawing as the real backdrop, in a card.
        Box(Modifier.fillMaxWidth().height(120.dp).clip(GlassShapes.card)) {
            Backdrop(tint = Color.Unspecified, custom = draft) {}
        }

        Text("저장한 배경", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (BUILT_IN + settings.backgroundPresets).forEach { preset ->
                val own = preset in settings.backgroundPresets
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = draft == preset.gradient,
                        onClick = { draft = preset.gradient },
                        label = { Text(preset.name, maxLines = 1) },
                    )
                    if (own) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "'${preset.name}' 지우기",
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { app.settings.setBackgroundPresets(settings.backgroundPresets - preset) }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                placeholder = { Text("이름") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                "지금 배경 저장",
                onClick = {
                    val label = name.trim()
                    app.settings.setBackgroundPresets(settings.backgroundPresets.filter { it.name != label } + NamedGradient(label, draft))
                    name = ""
                },
                enabled = name.isNotBlank(),
            )
        }

        Text("색", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("가운데 색 쓰기", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = draft.middle != null,
                onCheckedChange = { on ->
                    draft = draft.copy(middle = if (on) mix(draft.start, draft.end) else null)
                    if (!on && target == 1) target = 0
                },
            )
        }
        val targets = if (draft.middle != null) listOf("시작", "가운데", "끝") else listOf("시작", "끝")
        val shown = target.coerceAtMost(targets.size - 1)
        PillTabs(labels = targets, selected = shown, onSelect = { target = it }, fill = true)
        val editing = when {
            shown == 0 -> draft.start
            draft.middle != null && shown == 1 -> draft.middle!!
            else -> draft.end
        }
        ColorPicker(
            color = Color(editing),
            onChange = { c ->
                val argb = c.toArgb().toLong() and 0xFFFFFFFFL
                draft = when {
                    shown == 0 -> draft.copy(start = argb)
                    draft.middle != null && shown == 1 -> draft.copy(middle = argb)
                    else -> draft.copy(end = argb)
                }
            },
        )

        Text("방향과 위치", style = MaterialTheme.typography.titleSmall)
        Value("각도", "${draft.angleDegrees.roundToInt()}°") {
            GlassSlider(value = draft.angleDegrees, onValueChange = { draft = draft.copy(angleDegrees = it.roundToInt().toFloat()) }, valueRange = 0f..359f)
        }
        Value("위치", "${(draft.offset * 100).roundToInt()}%") {
            GlassSlider(value = draft.offset, onValueChange = { draft = draft.copy(offset = (it * 100).roundToInt() / 100f) }, valueRange = -0.5f..0.5f)
        }
        Value("시작 색 위치", "${(draft.startStop * 100).roundToInt()}%") {
            GlassSlider(value = draft.startStop, onValueChange = { draft = draft.copy(startStop = it.coerceAtMost(draft.endStop)) })
        }
        if (draft.middle != null) {
            Value("가운데 색 위치", "${(draft.middleStop * 100).roundToInt()}%") {
                GlassSlider(value = draft.middleStop, onValueChange = { draft = draft.copy(middleStop = it.coerceIn(draft.startStop, draft.endStop)) })
            }
        }
        Value("끝 색 위치", "${(draft.endStop * 100).roundToInt()}%") {
            GlassSlider(value = draft.endStop, onValueChange = { draft = draft.copy(endStop = it.coerceAtLeast(draft.startStop)) })
        }
    }
}

@Composable
private fun Value(label: String, readout: String, slider: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.bodyMedium, color = GlassTheme.colors.inkMuted, modifier = Modifier.width(56.dp))
        }
        slider()
    }
}

private fun mix(a: Long, b: Long): Long {
    val c = androidx.compose.ui.graphics.lerp(Color(a), Color(b), 0.5f)
    return c.toArgb().toLong() and 0xFFFFFFFFL
}

private const val SAVE_DELAY_MS = 250L

/** Ready-made backgrounds; the first is the app's own. */
private val BUILT_IN = listOf(
    NamedGradient("기본 보라", GradientSpec.DEFAULT),
    NamedGradient("심야", GradientSpec(start = 0xFF1B2A4AL, end = 0xFF05070DL)),
    NamedGradient("노을", GradientSpec(start = 0xFF5A2A4AL, middle = 0xFF3A2440L, end = 0xFF0E0A12L, angleDegrees = 20f)),
    NamedGradient("숲", GradientSpec(start = 0xFF1F3A33L, end = 0xFF070D0BL, angleDegrees = 340f)),
    NamedGradient("새벽", GradientSpec(start = 0xFFE7E3F5L, end = 0xFFC9D6EEL, angleDegrees = 135f)),
)
