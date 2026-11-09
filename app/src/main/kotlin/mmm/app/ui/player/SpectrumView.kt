package mmm.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import android.media.audiofx.Visualizer
import mmm.app.player.SpectrumTap
import mmm.app.sessioneq.SessionEqService
import mmm.dsp.Fft
import mmm.dsp.Window
import mmm.eq.EqProfile
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * A live spectrum of what is playing, after the EQ: 4096-point FFTs of the tap's mono mix, read
 * out on a log frequency axis as bars, with falling peak markers. The active EQ's curve is drawn
 * over it, so a boost or cut can be seen landing on the music.
 *
 * Each bar takes the strongest bin in its slice of the spectrum, and slices below the FFT's
 * resolution borrow their nearest bin, so the bass is not drawn as gaps. Bars rise at once and
 * fall at a fixed rate, the way analyser displays have always behaved - instant attack is what
 * makes transients visible, and a slow fall is what makes the display readable.
 */
/** Where the spectrum display gets its audio: [size] mono samples of what is playing now. */
interface SpectrumSource {
    val sampleRate: Int
    val size: Int
    fun read(target: FloatArray): Boolean
}

/** The player's own output, after the EQ. */
object PlayerSpectrumSource : SpectrumSource {
    override val sampleRate: Int get() = SpectrumTap.sampleRate
    override val size: Int = 4096
    override fun read(target: FloatArray): Boolean = SpectrumTap.latest(target)
}

/**
 * Another app's audio, through the Visualizer attached to its session. Visualizer hands out 8-bit
 * samples, so the floor of this display sits around -48 dB; the shape above it is what counts.
 */
object VisualizerSpectrumSource : SpectrumSource {
    private var bytes = ByteArray(0)

    override val sampleRate: Int
        get() = SessionEqService.latestVisualizer?.let { runCatching { it.samplingRate / 1000 }.getOrNull() } ?: 48000

    override val size: Int = 1024

    override fun read(target: FloatArray): Boolean {
        val visualizer = SessionEqService.latestVisualizer ?: return false
        return runCatching {
            val capture = visualizer.captureSize
            if (bytes.size != capture) bytes = ByteArray(capture)
            if (visualizer.getWaveForm(bytes) != Visualizer.SUCCESS) return false
            val n = minOf(capture, target.size)
            for (i in 0 until n) target[i] = ((bytes[i].toInt() and 0xFF) - 128) / 128f
            for (i in n until target.size) target[i] = 0f
            true
        }.getOrDefault(false)
    }
}

@Composable
fun SpectrumView(
    playing: Boolean,
    eq: EqProfile?,
    modifier: Modifier = Modifier,
    source: SpectrumSource = PlayerSpectrumSource,
) {
    val analyzer = remember(source) { SpectrumAnalyzer(source) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playing) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                analyzer.update(playing, dt)
                frame = now
            }
        }
    }

    val barColor = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val eqColor = MaterialTheme.colorScheme.error

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            frame // read so the canvas redraws every frame
            val bars = analyzer.levels
            val peaks = analyzer.peaks
            val width = size.width / bars.size
            fun y(db: Double) = ((TOP_DB - db) / (TOP_DB - BOTTOM_DB) * size.height).toFloat().coerceIn(0f, size.height)

            for (db in listOf(-20.0, -40.0, -60.0, -80.0)) {
                drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
            }
            for (f in listOf(100.0, 1000.0, 10000.0)) {
                val x = (xOf(f) * size.width).toFloat()
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            }

            bars.forEachIndexed { i, level ->
                val top = y(level)
                drawRect(barColor, Offset(i * width + 1f, top), Size(width - 2f, size.height - top))
                val peak = y(peaks[i])
                drawLine(peakColor, Offset(i * width + 1f, peak), Offset((i + 1) * width - 1f, peak), 3f)
            }

            if (eq != null) {
                // The EQ's shape without its preamp, around the -30 dB line, for comparison only.
                val path = Path()
                for (step in 0..120) {
                    val f = 20.0 * 1000.0.pow(step / 120.0)
                    val gain = eq.magnitudeDbAt(f) - eq.preampDb
                    val px = (xOf(f) * size.width).toFloat()
                    val py = y(EQ_BASELINE_DB + gain * EQ_SCALE)
                    if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, eqColor, style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
        }
        Text(
            "20 Hz · 100 · 1k · 10k · 20 kHz · 눈금 20 dB · 막대 = EQ 적용 후 출력 · 점선 = 이어폰 EQ 모양(×${EQ_SCALE.toInt()})",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private const val TOP_DB = 0.0
private const val BOTTOM_DB = -96.0
private const val EQ_BASELINE_DB = -30.0
private const val EQ_SCALE = 2.0
private const val BARS = 72

private val LOG_MIN = ln(20.0)
private val LOG_MAX = ln(20000.0)

/** Horizontal position of [frequency] on the log axis, 0..1. */
private fun xOf(frequency: Double): Double = (ln(frequency) - LOG_MIN) / (LOG_MAX - LOG_MIN)

private class SpectrumAnalyzer(private val source: SpectrumSource) {
    private val size = source.size
    private val fft = Fft(size)
    private val window = Window.hann(size)
    private val samples = FloatArray(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    val levels = DoubleArray(BARS) { BOTTOM_DB }
    val peaks = DoubleArray(BARS) { BOTTOM_DB }
    private val peakHold = DoubleArray(BARS)

    fun update(playing: Boolean, dt: Double) {
        val fresh = playing && source.read(samples)
        val rate = source.sampleRate
        val target = DoubleArray(BARS) { BOTTOM_DB }
        if (fresh) {
            for (i in 0 until size) {
                re[i] = (samples[i] * window[i]).toDouble()
                im[i] = 0.0
            }
            fft.forward(re, im)
            // Hann window coherent gain is 0.5; this scaling reads a full-scale sine as 0 dB.
            val scale = 4.0 / size
            val binHz = rate.toDouble() / size
            for (b in 0 until BARS) {
                val lowHz = 20.0 * 1000.0.pow(b.toDouble() / BARS)
                val highHz = 20.0 * 1000.0.pow((b + 1).toDouble() / BARS)
                val from = (lowHz / binHz).toInt().coerceIn(1, size / 2)
                val to = (highHz / binHz).toInt().coerceIn(from, size / 2)
                var best = 0.0
                for (bin in from..to) {
                    val m = kotlin.math.hypot(re[bin], im[bin]) * scale
                    if (m > best) best = m
                }
                target[b] = (20 * log10(best.coerceAtLeast(1e-9))).coerceIn(BOTTOM_DB, TOP_DB)
            }
        }
        for (b in 0 until BARS) {
            levels[b] = if (target[b] >= levels[b]) target[b] else maxOf(target[b], levels[b] - FALL_DB_PER_S * dt)
            if (levels[b] >= peaks[b]) {
                peaks[b] = levels[b]
                peakHold[b] = PEAK_HOLD_S
            } else if (peakHold[b] > 0) {
                peakHold[b] -= dt
            } else {
                peaks[b] = maxOf(levels[b], peaks[b] - PEAK_FALL_DB_PER_S * dt)
            }
        }
    }

    private companion object {
        const val FALL_DB_PER_S = 60.0
        const val PEAK_HOLD_S = 0.8
        const val PEAK_FALL_DB_PER_S = 20.0
    }
}
