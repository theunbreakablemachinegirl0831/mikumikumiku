package mmm.app.ui.components.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CardColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassTheme

/**
 * A card: a subtle pane lying in the content. Called like Material's Card, so screens written
 * against that keep their meaning - a [colors] container colour, when given, tints the pane.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(tint = colors?.containerColor), content = content)
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(tint = colors?.containerColor).clickable(onClick = onClick), content = content)
}

/** Rows grouped on one pane, with hairlines between them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(), content = content)
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = GlassDimensions.cardPadding), color = GlassTheme.colors.inkFaint.copy(alpha = 0.14f))
}

/** A small label over a group of rows, as in the reference's settings ("일반", "출력"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = GlassTheme.colors.inkMuted,
        modifier = modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
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
            .padding(horizontal = GlassDimensions.cardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = GlassTheme.colors.inkMuted)
        }
    }
}
