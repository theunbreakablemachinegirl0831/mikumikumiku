package mmm.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The app's corners. Few on purpose: a radius that differs by a few dp from its neighbour reads as
 * a mistake, so everything is one of these.
 */
object GlassShapes {
    /** Buttons, chips, tabs, the navigation bar's lens: anything pressed. */
    val pill: Shape = RoundedCornerShape(percent = 50)
    val circle: Shape = CircleShape
    /** Cards and grouped rows. */
    val card: Shape = RoundedCornerShape(24.dp)
    /** Small panes inside a card: a format badge, a note. */
    val chip: Shape = RoundedCornerShape(14.dp)
    /** Floating chrome: the bottom panel and dialogs. */
    val floating: Shape = RoundedCornerShape(30.dp)
    val sheet: Shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val menu: Shape = RoundedCornerShape(20.dp)
    /** The large artwork. */
    val artwork: Shape = RoundedCornerShape(28.dp)
}
