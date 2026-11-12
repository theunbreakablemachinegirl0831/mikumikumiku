package mmm.app.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mmm.app.data.PeqBand
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.visual.SpectrumState
import mmm.app.ui.visual.SpectrumLayers
import mmm.app.ui.visual.SpectrumPaint
import mmm.app.ui.visual.drawSpectrumLayers
import mmm.eq.EqFilter
import mmm.eq.EqProfile
import mmm.eq.FrequencyResponse
import kotlin.math.ln
import kotlin.math.pow

/**
 * The EQ as the reference draws it: the curve of everything that is switched on, over a grid of
 * decades and 6 dB lines, with the music's spectrum moving behind it so a boost can be watched
 * landing on what is playing.
 *
 * In the parametric tab the bands are numbered handles on the curve. Dragging one moves its
 * frequency and gain together - the fastest way to find where something sits by ear.
 *
 * @param total every filter that is playing, all layers together
 * @param layer the layer being edited on its own, drawn faintly, or null
 * @param nodes the parametric bands to show as handles; empty outside the parametric tab
 */
@Composable
fun EqGraph(
    total: List<EqFilter>,
    spectrum: SpectrumState?,
    modifier: Modifier = Modifier,
    layer: List<EqFilter>? = null,
    spectrumPaint: SpectrumPaint? = null,
    spectrumLayers: SpectrumLayers = SpectrumLayers(weighted = false, showRaw = false, showWeighted = true),
    active: Boolean = true,
    nodes: List<PeqBand> = emptyList(),
    selectedNode: Int = -1,
    onSelectNode: (Int) -> Unit = {},
    onMoveNode: (index: Int, frequency: Double, gainDb: Double) -> Unit = { _, _, _ -> },
) {
    val colors = GlassTheme.colors
    val ink = colors.ink
    val accent = colors.accent
    val onAccent = colors.onAccent
    val totalCurve = remember(total) { EqProfile(total, 0.0).response(GRID).decibels }
    val layerCurve = remember(layer) { layer?.let { EqProfile(it, 0.0).response(GRID).decibels } }
    val measurer = rememberTextMeasurer()
    val currentNodes by rememberUpdatedState(nodes)
    val select by rememberUpdatedState(onSelectNode)
    val move by rememberUpdatedState(onMoveNode)
    val labelStyle = TextStyle(color = colors.inkMuted, fontSize = 11.sp)
    // The gutters are as wide and tall as the labels really are in this font and size, so no
    // label is ever cut at the edge, whatever the listener's font scale.
    val gutter = remember(measurer, labelStyle) {
        val widest = listOf("+12", "-12").maxOf { measurer.measure(it, labelStyle).size.width }
        Gutter(left = widest.toFloat(), bottom = measurer.measure("10k", labelStyle).size.height.toFloat())
    }

    Column(modifier) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .pointerInput(gutter) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val plot = plotOf(Size(size.width.toFloat(), size.height.toFloat()), density, gutter)
                    val reach = HANDLE_REACH_DP * density
                    // The nearest handle within reach is taken; a touch elsewhere is left for scrolling.
                    val index = currentNodes.indices
                        .map { it to (Offset(xOf(currentNodes[it].frequency, plot), yOf(currentNodes[it].gainDb, plot)) - down.position).getDistance() }
                        .filter { it.second <= reach }
                        .minByOrNull { it.second }
                        ?.first ?: return@awaitEachGesture
                    select(index)
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        move(index, frequencyAt(change.position.x, plot), gainAt(change.position.y, plot))
                    }
                }
            },
    ) {
        val plot = plotOf(size, density, gutter)

        for (db in listOf(12.0, 6.0, 0.0, -6.0, -12.0)) {
            val y = yOf(db, plot)
            drawLine(ink.copy(alpha = if (db == 0.0) 0.28f else 0.10f), Offset(plot.left, y), Offset(plot.right, y), 1f)
            if (db != 0.0) {
                val text = measurer.measure((if (db > 0) "+" else "") + db.toInt(), labelStyle)
                drawText(text, topLeft = Offset(plot.left - text.size.width - 6f * density, y - text.size.height / 2f))
            }
        }
        for (f in GRID_LINES) {
            val x = xOf(f, plot)
            val decade = f == 100.0 || f == 1000.0 || f == 10000.0
            drawLine(ink.copy(alpha = if (decade) 0.22f else 0.08f), Offset(x, plot.top), Offset(x, plot.bottom), 1f)
        }
        for ((f, label) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(xOf(f, plot) - text.size.width / 2f, plot.bottom + 4f * density))
        }

        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            if (spectrum != null) {
                // A chosen colour is drawn stronger than the default ink: it is there to be seen.
                val chosen = spectrumPaint != null
                drawSpectrumLayers(
                    spectrum,
                    spectrumLayers,
                    plot,
                    paint = spectrumPaint ?: SpectrumPaint(ink),
                    fillAlpha = if (chosen) 0.30f else 0.20f,
                    lineAlpha = if (chosen) 0.70f else 0.35f,
                    gain = 0.92f,
                )
            }

            if (layerCurve != null) drawPath(curve(layerCurve, plot), ink.copy(alpha = 0.3f), style = Stroke(1.5f * density))

            val line = curve(totalCurve, plot)
            val fill = curve(totalCurve, plot, closeAt = yOf(0.0, plot))
            val strength = if (active) 1f else 0.4f
            drawPath(fill, ink.copy(alpha = 0.12f * strength))
            drawPath(line, ink.copy(alpha = 0.95f * strength), style = Stroke(2.5f * density))
        }

        nodes.forEachIndexed { index, band ->
            val centre = Offset(xOf(band.frequency, plot), yOf(band.gainDb, plot).coerceIn(plot.top, plot.bottom))
            val selected = index == selectedNode
            val radius = (if (selected) 13f else 11f) * density
            drawCircle(
                when {
                    selected -> accent
                    band.enabled -> HANDLE
                    else -> HANDLE.copy(alpha = 0.45f)
                },
                radius,
                centre,
            )
            val text = measurer.measure(
                (index + 1).toString(),
                TextStyle(color = if (selected) onAccent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
            drawText(text, topLeft = centre - Offset(text.size.width / 2f, text.size.height / 2f))
        }
    }
    if (spectrum != null) Legend(spectrumPaint?.start ?: ink, layered = layer != null, raw = spectrumLayers.weighted && spectrumLayers.showRaw)
    }
}

/** What each mark on the graph is: the EQ's own curve, the music's spectrum, and so on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(spectrumColor: Color, layered: Boolean, raw: Boolean) {
    val ink = GlassTheme.colors.ink
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem("EQ 곡선") { drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5f * density) }
        if (layered) LegendItem("이 탭의 단계") { drawLine(ink.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f * density) }
        LegendItem("재생 중 스펙트럼") { drawRect(spectrumColor.copy(alpha = 0.35f)) }
        if (raw) {
            LegendItem("가중 전 스펙트럼") {
                drawLine(
                    spectrumColor,
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    1.2f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density)),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, mark: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 16.dp, height = 8.dp), onDraw = mark)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GlassTheme.colors.inkMuted, maxLines = 1)
    }
}

private class Gutter(val left: Float, val bottom: Float)

private val GRID: List<Double> = FrequencyResponse.logGrid(20.0, 20000.0, 24)
private val GRID_LINES = listOf(30.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)

/** A handle that is not selected: a mid grey that carries white numerals on either theme. */
private val HANDLE = Color(0xFF8E8E98)

private const val LOW_HZ = 20.0
private const val HIGH_HZ = 20000.0
private const val RANGE_DB = 15.0
private const val HANDLE_REACH_DP = 30f

/** The area the curve is drawn in: the canvas less a gutter on the left for dB and below for Hz, each sized to its labels. */
private fun plotOf(size: Size, density: Float, gutter: Gutter): Rect =
    Rect(gutter.left + 10f * density, 10f * density, size.width - 14f * density, size.height - gutter.bottom - 8f * density)

private fun xOf(frequency: Double, plot: Rect): Float =
    plot.left + (ln(frequency.coerceIn(LOW_HZ, HIGH_HZ) / LOW_HZ) / ln(HIGH_HZ / LOW_HZ)).toFloat() * plot.width

private fun yOf(db: Double, plot: Rect): Float =
    plot.center.y - (db / RANGE_DB).toFloat() * plot.height / 2f

private fun frequencyAt(x: Float, plot: Rect): Double =
    LOW_HZ * (HIGH_HZ / LOW_HZ).pow(((x - plot.left) / plot.width).coerceIn(0f, 1f).toDouble())

private fun gainAt(y: Float, plot: Rect): Double =
    ((plot.center.y - y) / (plot.height / 2f) * RANGE_DB).coerceIn(-RANGE_DB, RANGE_DB)

/** The response as a line, or - with [closeAt] - as the area between it and that height. */
private fun curve(levels: List<Double>, plot: Rect, closeAt: Float? = null): Path {
    val path = Path()
    if (closeAt != null) path.moveTo(plot.left, closeAt)
    levels.forEachIndexed { i, db ->
        val x = xOf(GRID[i], plot)
        val y = yOf(db, plot)
        if (i == 0 && closeAt == null) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (closeAt != null) {
        path.lineTo(plot.right, closeAt)
        path.close()
    }
    return path
}
