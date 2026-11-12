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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import mmm.app.data.TrainerSettings
import mmm.app.data.VisualizerColor
import mmm.app.data.VisualizerScaling
import mmm.app.data.VisualizerWeighting
import mmm.app.player.SpectrumTap
import mmm.app.sessioneq.SessionEqService
import mmm.app.ui.components.glass.LocalBackdropTint
import mmm.app.ui.theme.GlassTheme
import mmm.dsp.Fft
import mmm.dsp.FrequencyWeighting
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

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

/** How a spectrum display is to be computed: everything in the settings that changes the numbers. */
data class SpectrumOptions(
    val points: Int,
    val weighting: VisualizerWeighting = VisualizerWeighting.FLAT,
    val phon: Int = 60,
    val scaling: VisualizerScaling = VisualizerScaling.AUTO,
    val topDb: Double = -12.0,
    val rangeDb: Double = 66.0,
    val tilt: Boolean = true,
) {
    companion object {
        fun of(settings: TrainerSettings) = SpectrumOptions(
            points = settings.visualizerPoints,
            weighting = settings.visualizerWeighting,
            phon = settings.visualizerPhon,
            scaling = settings.visualizerScaling,
            topDb = settings.visualizerTopDb,
            rangeDb = settings.visualizerRangeDb,
            tilt = settings.visualizerTilt,
        )
    }
}

/**
 * Turns audio into curves: [points] levels from 20 Hz to 20 kHz on a log axis, kept twice - as
 * measured ([rawDb]) and with the frequency weighting applied ([weightedDb]) - and both mapped
 * for drawing into [raw] and [weighted], where 0 is the bottom of the display and 1 its top.
 *
 * Only the drawing is weighted. The audio, the EQ and everything else downstream of the player
 * never see these numbers.
 *
 * The FFT grows with the resolution asked for, because a point narrower than a bin has nothing
 * of its own to show: 16384 samples at the finest setting, a third of a second, which is what
 * separates neighbouring notes in the bass. Where even that is too coarse the level is
 * interpolated between bins rather than drawn as steps. The weighting is computed once per bin
 * at that bin's own centre frequency, and again only when the sample rate or the weighting
 * changes.
 *
 * Nothing is clipped at the top. In automatic scaling the top of the display follows the loudest
 * point, rising at once and settling back slowly; in manual scaling it is where the listener put
 * it, and what goes above is simply drawn above.
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
    private val binDb = DoubleArray(size / 2 + 1)
    private val weights = DoubleArray(size / 2 + 1)
    private val tilts = DoubleArray(size / 2 + 1)
    private var weightsFor: Triple<Int, VisualizerWeighting, Pair<Int, Boolean>>? = null
    private val targetRaw = DoubleArray(points)
    private val targetWeighted = DoubleArray(points)

    /** Smoothed levels in dB (re a full-scale sine), unweighted and weighted. */
    val rawDb = DoubleArray(points) { SILENCE_DB }
    val weightedDb = DoubleArray(points) { SILENCE_DB }

    /** The same, mapped for drawing. Read on the thread that calls [update]. */
    val raw = FloatArray(points)
    val weighted = FloatArray(points)

    /** The level at the top of the display now, in dB. */
    var topDb = -12.0
        private set

    fun update(source: SpectrumSource, playing: Boolean, seconds: Double, options: SpectrumOptions) {
        val rate = source.sampleRate
        val count = if (playing) source.read(samples) else 0
        val weighted = options.weighting != VisualizerWeighting.FLAT
        if (count >= MIN_SAMPLES) {
            prepareWeights(rate, options)
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
            // A Hann window passes half the amplitude, split over two bins: this reads a full-scale sine as 0 dB.
            val scale = 4.0 / n
            for (bin in 0..size / 2) binDb[bin] = 20 * log10((hypot(re[bin], im[bin]) * scale).coerceAtLeast(1e-9)) + tilts[bin]

            val binHz = rate.toDouble() / size
            val top = size / 2
            for (p in 0 until points) {
                val lowHz = LOW_HZ * SPAN.pow(p.toDouble() / points)
                val highHz = LOW_HZ * SPAN.pow((p + 1).toDouble() / points)
                // Nothing at or above Nyquist is analysed or shown.
                if (lowHz >= rate / 2.0) {
                    targetRaw[p] = SILENCE_DB
                    targetWeighted[p] = SILENCE_DB
                    continue
                }
                val from = (lowHz / binHz).toInt()
                val to = (highHz / binHz).toInt()
                if (to <= from) {
                    val position = (sqrt(lowHz * highHz) / binHz).coerceIn(1.0, top - 1.0)
                    val bin = position.toInt()
                    val t = position - bin
                    val rawHere = binDb[bin] * (1 - t) + binDb[bin + 1] * t
                    targetRaw[p] = rawHere
                    targetWeighted[p] = rawHere + weights[bin] * (1 - t) + weights[bin + 1] * t
                } else {
                    var bestRaw = SILENCE_DB
                    var bestWeighted = SILENCE_DB
                    for (bin in from.coerceIn(1, top)..to.coerceIn(1, top)) {
                        if (binDb[bin] > bestRaw) bestRaw = binDb[bin]
                        val w = binDb[bin] + weights[bin]
                        if (w > bestWeighted) bestWeighted = w
                    }
                    targetRaw[p] = bestRaw
                    targetWeighted[p] = bestWeighted
                }
            }
        } else {
            targetRaw.fill(SILENCE_DB)
            targetWeighted.fill(SILENCE_DB)
        }

        val rise = 1 - exp(-seconds / RISE_SECONDS)
        val fall = 1 - exp(-seconds / FALL_SECONDS)
        var peak = SILENCE_DB
        for (p in 0 until points) {
            rawDb[p] += (targetRaw[p] - rawDb[p]) * (if (targetRaw[p] > rawDb[p]) rise else fall)
            weightedDb[p] += (targetWeighted[p] - weightedDb[p]) * (if (targetWeighted[p] > weightedDb[p]) rise else fall)
            peak = max(peak, if (weighted) max(rawDb[p], weightedDb[p]) else rawDb[p])
        }

        topDb = when (options.scaling) {
            VisualizerScaling.MANUAL -> options.topDb
            VisualizerScaling.AUTO -> {
                val wanted = peak + AUTO_HEADROOM_DB
                when {
                    peak <= SILENCE_DB + 1 -> topDb
                    wanted > topDb -> wanted
                    else -> max(wanted, topDb - AUTO_SETTLE_DB_PER_S * seconds)
                }
            }
        }
        val floor = topDb - options.rangeDb
        for (p in 0 until points) {
            raw[p] = ((rawDb[p] - floor) / options.rangeDb).toFloat().coerceAtLeast(0f)
            this.weighted[p] = ((weightedDb[p] - floor) / options.rangeDb).toFloat().coerceAtLeast(0f)
        }
    }

    /** One weight per FFT bin, at the bin's own centre frequency, plus the display tilt. */
    private fun prepareWeights(rate: Int, options: SpectrumOptions) {
        val key = Triple(rate, options.weighting, options.phon to options.tilt)
        if (key == weightsFor) return
        weightsFor = key
        val binHz = rate.toDouble() / size
        for (bin in weights.indices) {
            val f = (bin * binHz).coerceAtLeast(binHz / 2)
            val tilt = if (options.tilt) TILT_DB_PER_OCTAVE * (ln(f / 1000.0) / LN2) else 0.0
            val weight = when (options.weighting) {
                VisualizerWeighting.FLAT -> 0.0
                VisualizerWeighting.A -> FrequencyWeighting.aWeightingDb(f)
                VisualizerWeighting.EQUAL_LOUDNESS -> FrequencyWeighting.equalLoudnessWeightDb(f, options.phon.toDouble())
            }
            weights[bin] = weight
            // The tilt is part of how the display reads, so both curves get it; only the
            // weighting separates them.
            tilts[bin] = tilt
        }
    }

    private companion object {
        const val LOW_HZ = 20.0
        const val SPAN = 1000.0
        const val SILENCE_DB = -140.0
        const val TILT_DB_PER_OCTAVE = 3.0
        const val RISE_SECONDS = 0.03
        const val FALL_SECONDS = 0.22
        const val MIN_SAMPLES = 256
        const val AUTO_HEADROOM_DB = 3.0
        const val AUTO_SETTLE_DB_PER_S = 6.0
        val LN2 = ln(2.0)
    }
}

/** A running spectrum for drawing: read [frame] inside a draw block and it redraws every frame. */
@Stable
class SpectrumState(val analyzer: SpectrumAnalyzer) {
    var frame by mutableLongStateOf(0L)
        internal set
    val raw: FloatArray get() = analyzer.raw
    val weighted: FloatArray get() = analyzer.weighted
}

/** Keeps a spectrum of [source] moving while this is on screen. */
@Composable
fun rememberSpectrum(source: SpectrumSource, playing: Boolean, options: SpectrumOptions): SpectrumState {
    val state = remember(options.points) { SpectrumState(SpectrumAnalyzer(options.points)) }
    val currentSource by rememberUpdatedState(source)
    val currentPlaying by rememberUpdatedState(playing)
    val currentOptions by rememberUpdatedState(options)
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.1)
                last = now
                state.analyzer.update(currentSource, currentPlaying, seconds, currentOptions)
                state.frame = now
            }
        }
    }
    return state
}

/** A colour for a spectrum, or two for a gradient from the bass end to the treble end. */
@Stable
data class SpectrumPaint(val start: Color, val end: Color = start) {
    val gradient: Boolean get() = start != end
}

/**
 * The paint for the listener's colour choice.
 *
 * The same choice has to work in two places that have nothing in common: over artwork, which
 * can be any colour and is given a dark scrim, and on the EQ graph, which is drawn on the theme's
 * ground. So the default is white on artwork and the ink on the graph, the album colour is lifted
 * towards white where the ground is dark and deepened where it is light, and a picked colour is
 * deepened on a light graph, where a pastel would otherwise disappear.
 *
 * @param onArtwork drawn over a picture rather than on the theme's ground
 */
@Composable
fun visualizerPaint(settings: TrainerSettings, onArtwork: Boolean): SpectrumPaint {
    val colors = GlassTheme.colors
    val bright = onArtwork || colors.isDark
    val tint = LocalBackdropTint.current
    fun fit(c: Color) = if (bright) c else lerp(c, Color.Black, 0.35f)
    return when (val choice = settings.visualizerColor) {
        VisualizerColor.DEFAULT -> SpectrumPaint(if (onArtwork) Color.White else colors.ink)
        VisualizerColor.ALBUM -> SpectrumPaint(
            if (bright) lerp(tint, Color.White, if (onArtwork) 0.6f else 0.45f) else lerp(tint, Color.Black, 0.3f)
        )
        VisualizerColor.CUSTOM -> SpectrumPaint(fit(Color(settings.visualizerCustom)))
        VisualizerColor.GRADIENT -> SpectrumPaint(fit(Color(settings.visualizerGradientStart)), fit(Color(settings.visualizerGradientEnd)))
        else -> SpectrumPaint(fit(Color(choice.argb ?: 0xFFFFFFFF)))
    }
}

/** The swatch colour for a preset choice, as it looks over artwork. */
@Composable
fun swatchColor(choice: VisualizerColor, settings: TrainerSettings): Color = when (choice) {
    VisualizerColor.DEFAULT -> Color.White
    VisualizerColor.ALBUM -> lerp(LocalBackdropTint.current, Color.White, 0.6f)
    VisualizerColor.CUSTOM -> Color(settings.visualizerCustom)
    VisualizerColor.GRADIENT -> Color(settings.visualizerGradientStart)
    else -> Color(choice.argb ?: 0xFFFFFFFF)
}

/**
 * Draws [levels] as a filled curve rising from the bottom of [area], brightest at its crest. A
 * level above 1 would leave the area; it is held at the area's top edge, which with automatic
 * scaling does not happen.
 */
fun DrawScope.drawSpectrum(
    levels: FloatArray,
    area: Rect = Rect(Offset.Zero, size),
    paint: SpectrumPaint = SpectrumPaint(Color.White),
    fillAlpha: Float = 0.38f,
    lineAlpha: Float = 0.9f,
    gain: Float = 1f,
) {
    if (levels.isEmpty() || area.width <= 0f || area.height <= 0f) return
    val line = curvePath(levels, area, gain)
    val fill = Path()
    fill.moveTo(area.left, area.bottom)
    val step = area.width / (levels.size - 1).coerceAtLeast(1)
    for (i in levels.indices) fill.lineTo(area.left + i * step, yOf(levels[i], area, gain))
    fill.lineTo(area.right, area.bottom)
    fill.close()
    val fillBrush = if (paint.gradient) {
        Brush.horizontalGradient(listOf(paint.start.copy(alpha = fillAlpha), paint.end.copy(alpha = fillAlpha)), startX = area.left, endX = area.right)
    } else {
        Brush.verticalGradient(
            listOf(paint.start.copy(alpha = fillAlpha), paint.start.copy(alpha = fillAlpha * 0.15f)),
            startY = area.top,
            endY = area.bottom,
        )
    }
    drawPath(fill, fillBrush)
    val lineBrush = Brush.horizontalGradient(
        listOf(paint.start.copy(alpha = lineAlpha), paint.end.copy(alpha = lineAlpha)),
        startX = area.left,
        endX = area.right,
    )
    drawPath(line, lineBrush, style = Stroke(width = 1.6f * density))
}

/** A spectrum as a thin dashed line only, for comparing against a filled one. */
fun DrawScope.drawSpectrumOutline(levels: FloatArray, area: Rect, color: Color, gain: Float = 1f) {
    if (levels.isEmpty() || area.width <= 0f || area.height <= 0f) return
    drawPath(
        curvePath(levels, area, gain),
        color,
        style = Stroke(width = 1.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))),
    )
}

private fun yOf(level: Float, area: Rect, gain: Float): Float = area.bottom - (level * gain).coerceIn(0f, 1f) * area.height

private fun curvePath(levels: FloatArray, area: Rect, gain: Float): Path {
    val step = area.width / (levels.size - 1).coerceAtLeast(1)
    val path = Path()
    for (i in levels.indices) {
        val x = area.left + i * step
        val y = yOf(levels[i], area, gain)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/** Which of the analyzer's curves to draw, from the settings. */
@Stable
data class SpectrumLayers(val weighted: Boolean, val showRaw: Boolean, val showWeighted: Boolean) {
    companion object {
        fun of(settings: TrainerSettings) = SpectrumLayers(
            weighted = settings.visualizerWeighting != VisualizerWeighting.FLAT,
            showRaw = settings.visualizerShowRaw,
            showWeighted = settings.visualizerShowWeighted,
        )
    }
}

/**
 * Draws a running spectrum as the settings ask: unweighted alone when no weighting is chosen;
 * otherwise the weighted curve filled, with the unweighted one as a dashed line over it when
 * asked for, or filled on its own when the weighted one is turned off.
 */
fun DrawScope.drawSpectrumLayers(
    state: SpectrumState,
    layers: SpectrumLayers,
    area: Rect,
    paint: SpectrumPaint,
    fillAlpha: Float,
    lineAlpha: Float,
    gain: Float = 1f,
) {
    state.frame // read so this redraws every frame
    if (!layers.weighted) {
        drawSpectrum(state.raw, area, paint, fillAlpha, lineAlpha, gain)
        return
    }
    if (layers.showWeighted) {
        drawSpectrum(state.weighted, area, paint, fillAlpha, lineAlpha, gain)
        if (layers.showRaw) drawSpectrumOutline(state.raw, area, paint.end.copy(alpha = lineAlpha * 0.6f), gain)
    } else {
        drawSpectrum(state.raw, area, paint, fillAlpha, lineAlpha, gain)
    }
}
