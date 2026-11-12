package mmm.app.ui.earphone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import mmm.eq.FrequencyResponse
import kotlin.math.ln

data class GraphCurve(
    val label: String,
    val response: FrequencyResponse,
    val color: Color,
    val dashed: Boolean = false,
)

/**
 * Frequency responses on the axes every measurement site uses: 20 Hz to 20 kHz on a log scale,
 * level in dB. The curves are expected already normalised to the same reference, so the
 * vertical range simply fits them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResponseGraph(curves: List<GraphCurve>, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.outline
    val points = FrequencyResponse.logGrid(20.0, 20000.0, 12)
    val levels = curves.flatMap { c -> points.map { c.response.at(it) } }
    val top = ((levels.maxOrNull() ?: 10.0) / 5).let { kotlin.math.ceil(it) * 5 }.coerceAtLeast(5.0)
    val bottom = ((levels.minOrNull() ?: -10.0) / 5).let { kotlin.math.floor(it) * 5 }.coerceAtMost(-5.0)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val minLog = ln(20.0)
            val maxLog = ln(20000.0)
            fun x(f: Double) = ((ln(f) - minLog) / (maxLog - minLog) * size.width).toFloat()
            fun y(db: Double) = ((top - db) / (top - bottom) * size.height).toFloat()

            for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
                drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 1f)
            }
            var db = bottom
            while (db <= top) {
                drawLine(if (db == 0.0) axis else grid, Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1f)
                db += 5.0
            }

            for (curve in curves) {
                val path = Path()
                points.forEachIndexed { i, f ->
                    val px = x(f)
                    val py = y(curve.response.at(f))
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path,
                    curve.color,
                    style = Stroke(
                        width = 2.5f,
                        pathEffect = if (curve.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null,
                    ),
                )
            }
        }
        Text(
            "20 Hz · 100 · 1k · 10k · 20 kHz (로그 축) · 세로 ${bottom.toInt()}~${top.toInt()} dB, 5 dB 눈금",
            style = MaterialTheme.typography.labelSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            curves.forEach { curve ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(curve.color))
                    Text(curve.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
