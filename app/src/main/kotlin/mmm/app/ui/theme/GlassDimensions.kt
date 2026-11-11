package mmm.app.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing and sizes shared across screens, so the same thing is the same size everywhere. */
object GlassDimensions {
    /** Screen content to the screen's edge. */
    val screenGutter = 16.dp
    /** Between cards in a column. */
    val stack = 12.dp
    /** Inside a card, from its edge to its content. */
    val cardPadding = 18.dp
    /** The floating panel's distance from the screen's edge. */
    val panelMargin = 12.dp
    val panelGap = 8.dp
    /** The rim of a pane. Drawn centred on the edge, so half of it shows. */
    val edge = 1.5.dp
    /** Android's minimum for anything touched. */
    val touchTarget = 48.dp
    val icon = 24.dp
    val navIcon = 27.dp
}
