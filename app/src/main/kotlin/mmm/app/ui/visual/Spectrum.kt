package mmm.app.ui.visual

import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import mmm.app.player.SpectrumTap
import mmm.app.sessioneq.SessionEqService
import mmm.dsp.Fft
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** Where a spectrum display gets its audio: mono samples of what is playing now. */
interface SpectrumSource {
    val sampleRate: Int

    /**
     * Fills the start of [target] with the newest samples.
     * @return how many were written; 0 when there is nothing to show
     */
    fun read(target: FloatArray): Int
}

/** The app's own player, after the EQ - what actually reaches the ear. */
object PlayerSpectrumSource : SpectrumSource {
    override val sampleRate: Int get() = SpectrumTap.sampleRate
    override fun read(target: FloatArray): Int = if (SpectrumTap.latest(target)) target.size else 0
}

/**
 * Another app's audio, through the Visualizer on its session. Visualizer hands out a short
 * 8-bit capture, so this spectrum is coarser and its floor sits around -48 dB; the shape above
 * that is what counts.
 */
object VisualizerSpectrumSource : SpectrumSource {
    private var bytes = ByteArray(0)

    override val sampleRate: Int
        get() = SessionEqService.latestVisualizer?.let { runCatching { it.samplingRate / 1000 }.getOrNull() } ?: 48000

    override fun read(target: FloatArray): Int {
        val visualizer = SessionEqService.latestVisualizer ?: return 0
        return runCatching {
            val capture = visualizer.captureSize
            if (bytes.size != capture) bytes = ByteArray(capture)
            if (visualizer.getWaveForm(bytes) != Visualizer.SUCCESS) return 0
            val n = minOf(capture, target.size)
            for (i in 0 until n) target[i] = ((bytes[i].toInt() and 0xFF) - 128) / 128f
            n
        }.getOrDefault(0)
    }
}

/**
 * Turns audio into a curve: [points] levels from 20 Hz to 20 kHz on a log axis, each 0 (silence)
 * to 1 (as loud as the display shows).
 *
 * The FFT grows with the resolution asked for, because a point narrower than a bin has nothing
 * of its own to show: 16384 samples at the finest setting, a third of a second, which is what
 * separates neighbouring notes in the bass. Where even that is too coarse the level is
 * interpolated between bins rather than drawn as steps. The levels rise fast and fall slowly and
 * are tilted up 3 dB an octave, the way music's energy falls, so the curve moves across its whole
 * width instead of leaning on the bass.
 */
class SpectrumAnalyzer(val points: Int) {

    private val size = when {
        points >= 480 -> 16384
        points >= 240 -> 8192
        else -> 4096
    }
    private val fft = Fft(size)
    private val samples = FloatArray(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val magnitude = DoubleArray(size / 2 + 1)
    private val target = FloatArray(points)

    /** Smoothed levels, 0..1. Read on the thread that calls [update]. */
    val levels = FloatArray(points)

    fun update(source: SpectrumSource, playing: Boolean, seconds: Double) {
        val count = if (playing) source.read(samples) else 0
        if (count >= MIN_SAMPLES) {
            val n = minOf(count, size)
            // Hann over the samples that exist, zeros after: a short capture is still a clean spectrum.
            for (i in 0 until n) {
                re[i] = samples[i] * (0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1)))
                im[i] = 0.0
            }
            for (i in n until size) {
                re[i] = 0.0
                im[i] = 0.0
            }
            fft.forward(re, im)
            // A Hann window passes half the amplitude, split over two bins: this reads a full-scale sine as 1.
            val scale = 4.0 / n
            for (bin in 0..size / 2) magnitude[bin] = hypot(re[bin], im[bin]) * scale

            val binHz = source.sampleRate.toDouble() / size
            val top = size / 2
            for (p in 0 until points) {
                val lowHz = LOW_HZ * SPAN.pow(p.toDouble() / points)
                val highHz = LOW_HZ * SPAN.pow((p + 1).toDouble() / points)
                val centre = kotlin.math.sqrt(lowHz * highHz)
                val from = (lowHz / binHz).toInt()
                val to = (highHz / binHz).toInt()
                val linear = if (to <= from) {
                    val position = (centre / binHz).coerceIn(1.0, top - 1.0)
                    val bin = position.toInt()
                    val t = position - bin
                    magnitude[bin] * (1 - t) + magnitude[bin + 1] * t
                } else {
                    var best = 0.0
                    for (bin in from.coerceIn(1, top)..to.coerceIn(1, top)) if (magnitude[bin] > best) best = magnitude[bin]
                    best
                }
                val db = 20 * log10(linear.coerceAtLeast(1e-9)) + TILT_DB_PER_OCTAVE * (ln(centre / 1000.0) / LN2)
                target[p] = ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
            }
        } else {
            target.fill(0f)
        }

        val rise = (1 - exp(-seconds / RISE_SECONDS)).toFloat()
        val fall = (1 - exp(-seconds / FALL_SECONDS)).toFloat()
        for (p in 0 until points) {
            val wanted = target[p]
            levels[p] += (wanted - levels[p]) * (if (wanted > levels[p]) rise else fall)
        }
    }

    private companion object {
        const val LOW_HZ = 20.0
        const val SPAN = 1000.0
        const val FLOOR_DB = -84.0
        const val CEILING_DB = -18.0
        const val TILT_DB_PER_OCTAVE = 3.0
        const val RISE_SECONDS = 0.03
        const val FALL_SECONDS = 0.22
        const val MIN_SAMPLES = 256
        val LN2 = ln(2.0)
    }
}

/** A running spectrum for drawing: read [frame] inside a draw block and it redraws every frame. */
@Stable
class SpectrumState(val analyzer: SpectrumAnalyzer) {
    var frame by mutableLongStateOf(0L)
        internal set
    val levels: FloatArray get() = analyzer.levels
}

/**
 * Keeps a spectrum of [source] moving while this is on screen.
 * @param points resolution: levels across 20 Hz - 20 kHz
 */
@Composable
fun rememberSpectrum(source: SpectrumSource, playing: Boolean, points: Int): SpectrumState {
    val state = remember(points) { SpectrumState(SpectrumAnalyzer(points)) }
    val currentSource by rememberUpdatedState(source)
    val currentPlaying by rememberUpdatedState(playing)
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.1)
                last = now
                state.analyzer.update(currentSource, currentPlaying, seconds)
                state.frame = now
            }
        }
    }
    return state
}

/**
 * Draws [levels] as a filled curve rising from the bottom of [area], brightest at its crest.
 * @param gain scales the height: 1 lets the loudest level reach the top of [area]
 */
fun DrawScope.drawSpectrum(
    levels: FloatArray,
    area: Rect = Rect(Offset.Zero, size),
    color: Color = Color.White,
    fillAlpha: Float = 0.38f,
    lineAlpha: Float = 0.9f,
    gain: Float = 1f,
) {
    if (levels.isEmpty() || area.width <= 0f || area.height <= 0f) return
    val step = area.width / (levels.size - 1).coerceAtLeast(1)
    val line = Path()
    val fill = Path()
    fill.moveTo(area.left, area.bottom)
    for (i in levels.indices) {
        val x = area.left + i * step
        val y = area.bottom - (levels[i] * gain).coerceIn(0f, 1f) * area.height
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(area.right, area.bottom)
    fill.close()
    drawPath(
        fill,
        Brush.verticalGradient(
            listOf(color.copy(alpha = fillAlpha), color.copy(alpha = fillAlpha * 0.15f)),
            startY = area.top,
            endY = area.bottom,
        ),
    )
    drawPath(line, color.copy(alpha = lineAlpha), style = Stroke(width = 1.6f * density))
}

/** The same levels as thin bars, for the album art, where a curve would hide too much of the picture. */
fun DrawScope.drawSpectrumBars(
    levels: FloatArray,
    area: Rect = Rect(Offset.Zero, size),
    color: Color = Color.White,
    alpha: Float = 0.78f,
) {
    if (levels.isEmpty() || area.width <= 0f || area.height <= 0f) return
    val step = area.width / levels.size
    val bar = (step * 0.62f).coerceAtLeast(1f)
    val brush = Brush.verticalGradient(
        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f)),
        startY = area.top,
        endY = area.bottom,
    )
    for (i in levels.indices) {
        val height = (levels[i].coerceIn(0f, 1f) * area.height).coerceAtLeast(1f)
        drawRect(brush, Offset(area.left + i * step + (step - bar) / 2, area.bottom - height), Size(bar, height))
    }
}
