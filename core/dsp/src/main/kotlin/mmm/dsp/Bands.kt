package mmm.dsp

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The frequency-band grids the Band ID exercises are built on.
 *
 * "How to Listen" style band identification works by boosting one band of a fixed grid and asking
 * which one it was; the grids get finer as the learner advances, which is exactly the
 * [Resolution] ladder below.
 */
public enum class BandResolution(public val bandsPerOctave: Int, public val label: String) {
    OCTAVE(1, "1/1 oct"),
    HALF_OCTAVE(2, "1/2 oct"),
    THIRD_OCTAVE(3, "1/3 oct"),
    SIXTH_OCTAVE(6, "1/6 oct"),
}

/** One band of a [BandGrid]. */
public data class Band(
    val index: Int,
    val centerHz: Double,
    val lowerHz: Double,
    val upperHz: Double,
    val bandwidthOctaves: Double,
) {
    /** What the answer buttons show: "125 Hz", "1.25 kHz", "16 kHz". */
    public val label: String
        get() = when {
            centerHz < 1000.0 -> "${formatHz(centerHz)} Hz"
            else -> "${formatHz(centerHz / 1000.0)} kHz"
        }

    private fun formatHz(value: Double): String {
        val rounded = if (value >= 100) value.roundToInt().toDouble() else (value * 100).roundToInt() / 100.0
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
    }
}

/**
 * A logarithmic band grid anchored at 1 kHz, the ISO convention.
 *
 * @param resolution how many bands per octave
 * @param lowHz lowest centre frequency to include
 * @param highHz highest centre frequency to include
 */
public class BandGrid(
    public val resolution: BandResolution,
    lowHz: Double = 31.5,
    highHz: Double = 16000.0,
) {
    public val bands: List<Band>

    init {
        val n = resolution.bandsPerOctave
        val bandwidthOctaves = 1.0 / n
        val ratio = 2.0.pow(bandwidthOctaves)
        val edgeRatio = 2.0.pow(bandwidthOctaves / 2.0)

        // Index of the grid step relative to the 1 kHz anchor.
        val firstStep = kotlin.math.ceil(n * log2(lowHz / 1000.0)).toInt()
        val lastStep = kotlin.math.floor(n * log2(highHz / 1000.0)).toInt()

        bands = (firstStep..lastStep).mapIndexed { i, step ->
            val center = 1000.0 * ratio.pow(step.toDouble())
            Band(
                index = i,
                centerHz = roundNicely(center),
                lowerHz = center / edgeRatio,
                upperHz = center * edgeRatio,
                bandwidthOctaves = bandwidthOctaves,
            )
        }
    }

    public val size: Int get() = bands.size

    public operator fun get(index: Int): Band = bands[index]

    /** The band whose centre is closest to [frequency]. */
    public fun nearest(frequency: Double): Band =
        bands.minBy { kotlin.math.abs(log2(it.centerHz) - log2(frequency)) }

    private fun log2(x: Double): Double = kotlin.math.ln(x) / kotlin.math.ln(2.0)

    private fun roundNicely(hz: Double): Double = when {
        hz < 100 -> (hz * 10).roundToInt() / 10.0
        hz < 1000 -> hz.roundToInt().toDouble()
        else -> (hz / 10).roundToInt() * 10.0
    }

    public companion object {
        /** ISO 266 preferred centre frequencies, for UI labels that should look familiar. */
        public val ISO_OCTAVE_CENTERS: List<Double> =
            listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    }
}
