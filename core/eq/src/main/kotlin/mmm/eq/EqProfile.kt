package mmm.eq

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadCascade
import mmm.dsp.BiquadCoefficients
import mmm.dsp.BiquadDesign
import java.util.Locale
import kotlin.math.pow

public enum class EqFilterType(public val apoCode: String) {
    PEAK("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),
}

/** One band of a parametric EQ, in the units Equalizer APO and AutoEq use. */
public data class EqFilter(
    val type: EqFilterType,
    val frequency: Double,
    val q: Double,
    val gainDb: Double,
) {
    public fun coefficients(sampleRate: Int): BiquadCoefficients = when (type) {
        EqFilterType.PEAK -> BiquadDesign.peaking(frequency, q, gainDb, sampleRate)
        EqFilterType.LOW_SHELF -> BiquadDesign.lowShelfQ(frequency, q, gainDb, sampleRate)
        EqFilterType.HIGH_SHELF -> BiquadDesign.highShelfQ(frequency, q, gainDb, sampleRate)
    }

    public fun magnitudeDbAt(frequency: Double, sampleRate: Int = DESIGN_RATE): Double =
        coefficients(sampleRate).magnitudeDbAt(frequency, sampleRate)

    internal companion object {
        /** Rate the fitter evaluates filters at. Playback rebuilds them for the real rate. */
        const val DESIGN_RATE = 48000
    }
}

/**
 * A parametric EQ: a gain stage and a list of filters.
 *
 * Stored in the Equalizer APO text format that AutoEq publishes (`Preamp: -5.1 dB`, then
 * `Filter 1: ON PK Fc 3100 Hz Gain 4.2 dB Q 1.20`), so a profile made here can be pasted into
 * other EQ apps and one from AutoEq can be loaded here.
 */
public data class EqProfile(
    val filters: List<EqFilter>,
    val preampDb: Double,
) {
    /** Combined response at [frequency], including the preamp. */
    public fun magnitudeDbAt(frequency: Double, sampleRate: Int = EqFilter.DESIGN_RATE): Double =
        preampDb + filters.sumOf { it.magnitudeDbAt(frequency, sampleRate) }

    public fun response(grid: List<Double> = FrequencyResponse.logGrid()): FrequencyResponse {
        // Designed once per filter rather than once per point: graphs redraw this while a slider moves.
        val designed = filters.map { it.coefficients(EqFilter.DESIGN_RATE) }
        return FrequencyResponse(grid, grid.map { f -> preampDb + designed.sumOf { it.magnitudeDbAt(f, EqFilter.DESIGN_RATE) } })
    }

    public fun createProcessor(): EqProcessor = EqProcessor(this)

    public fun toApoText(): String = buildString {
        append("Preamp: ").append(format(preampDb, 1)).append(" dB\n")
        filters.forEachIndexed { index, filter ->
            append("Filter ").append(index + 1).append(": ON ").append(filter.type.apoCode)
            append(" Fc ").append(format(filter.frequency, 0)).append(" Hz")
            append(" Gain ").append(format(filter.gainDb, 1)).append(" dB")
            append(" Q ").append(format(filter.q, 2)).append('\n')
        }
    }

    public companion object {
        public val FLAT: EqProfile = EqProfile(emptyList(), 0.0)

        private val PREAMP = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
        private val FILTER = Regex(
            """Filter\s*\d*:\s*ON\s+(PK|PEQ|LSC|LS|HSC|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
            RegexOption.IGNORE_CASE,
        )

        /** Reads the Equalizer APO format; lines it does not recognise are ignored. */
        public fun parseApo(text: String): EqProfile {
            val preamp = PREAMP.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val filters = FILTER.findAll(text).map { match ->
                val type = when (match.groupValues[1].uppercase()) {
                    "LSC", "LS" -> EqFilterType.LOW_SHELF
                    "HSC", "HS" -> EqFilterType.HIGH_SHELF
                    else -> EqFilterType.PEAK
                }
                EqFilter(
                    type = type,
                    frequency = match.groupValues[2].toDouble(),
                    q = match.groupValues[4].toDouble(),
                    gainDb = match.groupValues[3].toDouble(),
                )
            }.toList()
            return EqProfile(filters, preamp)
        }

        private fun format(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)
    }
}

/**
 * Plays an [EqProfile]: the preamp, then the filters in series, rebuilt for the stream's rate.
 *
 * It starts at its profile's gain - never at unity first - so the first block it plays is
 * already at the level it will keep. [update] swaps in another profile while playing: the
 * preamp glides to its new value over [GAIN_RAMP_SECONDS] instead of stepping, and when the new
 * profile has the same number of filters - a band being dragged, a gain being turned - the
 * coefficients change in place and the filters keep their state, so the change is heard as a
 * change and not as a click.
 */
public class EqProcessor internal constructor(profile: EqProfile) : AudioProcessor {

    private var profile: EqProfile = profile
    private var format: AudioFormat? = null
    private var cascade: BiquadCascade? = null
    private var gain = gainOf(profile)
    private var targetGain = gain
    private var gainStep = 0f
    private var rampFrames = 1

    /** The filters' layout: whether [update] with a profile of this shape can keep their state. */
    public val filterCount: Int get() = profile.filters.size

    override fun prepare(format: AudioFormat) {
        this.format = format
        cascade = build(format)
        rampFrames = (GAIN_RAMP_SECONDS * format.sampleRate).toInt().coerceAtLeast(1)
        gain = targetGain
        gainStep = 0f
    }

    /** Call from the thread that calls [process]. */
    public fun update(profile: EqProfile) {
        val format = this.format
        val current = cascade
        val sameShape = profile.filters.size == this.profile.filters.size
        this.profile = profile
        targetGain = gainOf(profile)
        gainStep = (targetGain - gain) / rampFrames
        if (format == null) {
            gain = targetGain
            return
        }
        if (current != null && sameShape) {
            profile.filters.forEachIndexed { index, filter ->
                current.setCoefficients(index, filter.coefficients(format.sampleRate))
            }
        } else {
            cascade = build(format)
        }
    }

    override fun process(buffer: AudioBuffer) {
        if (gain != targetGain) {
            val start = gain
            var end = start
            for (ch in 0 until buffer.channels) {
                val samples = buffer.data[ch]
                var g = start
                for (i in 0 until buffer.frames) {
                    if (g != targetGain) g = if (gainStep > 0) minOf(targetGain, g + gainStep) else maxOf(targetGain, g + gainStep)
                    samples[i] *= g
                }
                end = g
            }
            gain = end
        } else if (gain != 1f) {
            for (ch in 0 until buffer.channels) {
                val samples = buffer.data[ch]
                for (i in 0 until buffer.frames) samples[i] *= gain
            }
        }
        cascade?.process(buffer)
    }

    override fun reset() {
        cascade?.reset()
    }

    private fun build(format: AudioFormat): BiquadCascade {
        val stages = BiquadCascade(profile.filters.size, format.channels)
        profile.filters.forEachIndexed { index, filter ->
            stages.setCoefficients(index, filter.coefficients(format.sampleRate))
        }
        stages.prepare(format)
        return stages
    }

    private fun gainOf(profile: EqProfile): Float = 10.0.pow(profile.preampDb / 20.0).toFloat()

    public companion object {
        public const val GAIN_RAMP_SECONDS: Double = 0.02
    }
}
