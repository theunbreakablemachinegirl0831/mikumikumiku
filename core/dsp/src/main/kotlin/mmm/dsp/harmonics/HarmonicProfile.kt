package mmm.dsp.harmonics

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the orders above H3 fall away from H2 and H3. Each is a curve from 0 (no decay yet) to 1
 * (the whole decay) across the orders, and they differ in where the decay happens:
 *
 * - [LINEAR]: the same number of decibels every order.
 * - [SINE]: gently at first and last, fastest at the centre order, over a fixed width.
 * - [ARCTANGENT]: fastest at the centre, with long shoulders that never quite stop.
 * - [SIGMOID]: a plateau, a fall around the centre, and a floor - the most step-like.
 */
public enum class HarmonicAlgorithm(public val label: String) {
    LINEAR("Linear"),
    SINE("Sine"),
    ARCTANGENT("Arctangent"),
    SIGMOID("Sigmoid"),
    ;

    /** Whether the centre and curve-shape parameters change anything for this curve. */
    public val shaped: Boolean get() = this != LINEAR

    /**
     * The decay curve: 0 at [u] = 0, 1 at [u] = 1, never decreasing.
     * @param centre where the decay is steepest, as a fraction of the way across the orders
     * @param shape steepness; 1 is the curve's natural width, higher is sharper
     */
    public fun curve(u: Double, centre: Double, shape: Double): Double {
        val x = u.coerceIn(0.0, 1.0)
        if (this == LINEAR) return x
        val c = centre.coerceIn(0.0, 1.0)
        val k = shape.coerceIn(HarmonicSettings.SHAPE_MIN, HarmonicSettings.SHAPE_MAX)
        val f: (Double) -> Double = when (this) {
            // A half sine wave across a window 1/k wide, flat outside it.
            SINE -> { t -> sin((PI * k * (t - c)).coerceIn(-PI / 2, PI / 2)) }
            ARCTANGENT -> { t -> atan(ARCTAN_STEEPNESS * k * (t - c)) }
            SIGMOID -> { t -> 1.0 / (1.0 + exp(-SIGMOID_STEEPNESS * k * (t - c))) }
            LINEAR -> { t -> t }
        }
        val low = f(0.0)
        val high = f(1.0)
        // A centre at either end with a sharp curve leaves nothing to normalise: fall back to a line.
        if (high - low < 1e-9) return x
        return ((f(x) - low) / (high - low)).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val ARCTAN_STEEPNESS = 6.0
        const val SIGMOID_STEEPNESS = 10.0
    }
}

/** The settings of one tail curve. Each algorithm keeps its own, so switching back finds them as left. */
public data class TailParams(
    /** How strong the generated orders are, 0..1 as a linear amplitude. */
    val intensity: Double = 0.8,
    /** Average decay in dB per order, so the last order is this times the number of orders down. */
    val slopeDb: Double = 2.0,
    /** The order where the decay is steepest. */
    val centre: Int = 6,
    val shape: Double = 1.0,
    /** Cap on all harmonics together (RMS of their amplitudes), in the profile's dB. */
    val outputLimitDb: Double = 0.0,
)

/** One parity's controls: a level for the whole group and a tilt that reaches its full value at the top order. */
public data class GroupParams(val levelDb: Double = 0.0, val slopeDb: Double = 0.0)

/**
 * The harmonic make-up of a sound, as levels of H2, H3 and the orders above them.
 *
 * Levels are in dB on one scale, where 0 dB is [HarmonicSettings.REFERENCE_DB] below a full-scale
 * tone: what a full-scale tone at the profile's strongest setting grows. Quieter music grows
 * proportionally less, as from a valve - see [HarmonicProcessor].
 *
 * H2 and H3 are set directly. The orders above them are generated from the one of the same parity
 * - even ones from H2, odd ones from H3 - falling away along the chosen [algorithm]'s curve. The
 * even and odd groups then each get their level and tilt, [balance] tips between them, and any
 * order may be set by hand in [overrides].
 */
public data class HarmonicProfile(
    val h2Db: Double = 0.0,
    val h3Db: Double = -9.0,
    val algorithm: HarmonicAlgorithm = HarmonicAlgorithm.SIGMOID,
    val tails: Map<HarmonicAlgorithm, TailParams> = DEFAULT_TAILS,
    val even: GroupParams = GroupParams(),
    val odd: GroupParams = GroupParams(),
    /** -1 (only odd) .. 0 (as set) .. +1 (only even), applied to the linear amplitudes. */
    val balance: Double = 0.0,
    val maxOrder: Int = 10,
    /** Orders set by hand, in dB; they bypass the generated curve and the group level and tilt. */
    val overrides: Map<Int, Double> = emptyMap(),
) {
    public val tail: TailParams get() = tails[algorithm] ?: TailParams()

    /** The level an order would have from the curve and groups alone, ignoring any override. */
    public fun generatedDb(order: Int): Double {
        val top = maxOrder.coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
        if (order < 2 || order > top) return OFF
        val even = order % 2 == 0
        val base = when (order) {
            2 -> h2Db
            3 -> h3Db
            else -> {
                val anchor = if (even) 2 else 3
                val params = tail
                if (params.intensity <= 0.0) return OFF
                val span = (top - anchor).coerceAtLeast(1)
                val u = (order - anchor).toDouble() / span
                val centre = (params.centre - anchor).toDouble() / span
                val decay = params.slopeDb * span * algorithm.curve(u, centre, params.shape)
                (if (even) h2Db else h3Db) - decay + 20 * log10(params.intensity)
            }
        }
        val group = if (even) this.even else odd
        val first = if (even) 2 else 3
        // The tilt reaches its full value at the group's own top order, which is the max order
        // or the one below it.
        val last = if ((top - first) % 2 == 0) top else top - 1
        val tiltSpan = (last - first).coerceAtLeast(1)
        val tilt = group.slopeDb * (order - first) / tiltSpan
        return (base + group.levelDb + tilt).coerceAtMost(0.0)
    }

    /** The level each order is played at, in dB, before [balance]; [OFF] for orders not generated. */
    public fun levelDb(order: Int): Double {
        val top = maxOrder.coerceIn(HarmonicSettings.MIN_ORDER, HarmonicSettings.MAX_ORDER)
        if (order < 2 || order > top) return OFF
        return overrides[order] ?: generatedDb(order)
    }

    /**
     * Each order's amplitude relative to the reference, after the balance and the output limit:
     * index = order, 0 and 1 unused. Linear, because that is the domain in which intensity and
     * balance scale an effect without changing its proportions.
     */
    public fun relativeAmplitudes(): DoubleArray {
        val out = DoubleArray(HarmonicSettings.MAX_ORDER + 1)
        val evenScale = if (balance < 0) 1.0 + balance else 1.0
        val oddScale = if (balance > 0) 1.0 - balance else 1.0
        for (order in 2..HarmonicSettings.MAX_ORDER) {
            val db = levelDb(order)
            if (db <= OFF) continue
            out[order] = 10.0.pow(db / 20) * if (order % 2 == 0) evenScale else oddScale
        }
        val total = sqrt(out.sumOf { it * it })
        val limit = 10.0.pow(tail.outputLimitDb.coerceAtMost(0.0) / 20)
        if (total > limit) for (i in out.indices) out[i] *= limit / total
        return out
    }

    public companion object {
        /** Below every real level: an order that is not generated at all. */
        public const val OFF: Double = -1000.0

        public val DEFAULT_TAILS: Map<HarmonicAlgorithm, TailParams> = mapOf(
            HarmonicAlgorithm.LINEAR to TailParams(intensity = 0.7, slopeDb = 1.5),
            HarmonicAlgorithm.SINE to TailParams(intensity = 0.8, slopeDb = 2.0, centre = 6, shape = 1.0),
            HarmonicAlgorithm.ARCTANGENT to TailParams(intensity = 0.8, slopeDb = 2.0, centre = 6, shape = 1.0),
            HarmonicAlgorithm.SIGMOID to TailParams(intensity = 0.8, slopeDb = 2.5, centre = 6, shape = 1.0),
        )
    }
}

/**
 * The starting points. Each is a whole profile; [R2R_NOS] is [R2R]'s harmonics with the separate
 * NOS treble roll-off switched on, which is not a harmonic setting at all.
 */
public enum class HarmonicPreset(public val label: String, public val nos: Boolean) {
    TRIODE("3-Tube / Triode", false),
    PUSH_PULL("Push-Pull", false),
    R2R("R-2R", false),
    R2R_NOS("R-2R NOS", true),
    CUSTOM("Custom", false),
    ;

    /** The preset's profile; [CUSTOM] has none of its own. */
    public val profile: HarmonicProfile?
        get() = when (this) {
            // H2 leads, higher even orders fade, odd ones sit well below: single-ended valves.
            TRIODE -> HarmonicProfile(
                h2Db = 0.0,
                h3Db = -12.0,
                algorithm = HarmonicAlgorithm.SIGMOID,
                tails = HarmonicProfile.DEFAULT_TAILS + (HarmonicAlgorithm.SIGMOID to TailParams(0.8, 2.5, 6, 1.0, 0.0)),
                even = GroupParams(0.0, -6.0),
                odd = GroupParams(-6.0, -6.0),
                balance = 0.3,
                maxOrder = 10,
            )
            // H2 and H3 together, both parities carrying weight, a gentle fall above.
            PUSH_PULL -> HarmonicProfile(
                h2Db = -3.0,
                h3Db = 0.0,
                algorithm = HarmonicAlgorithm.ARCTANGENT,
                tails = HarmonicProfile.DEFAULT_TAILS + (HarmonicAlgorithm.ARCTANGENT to TailParams(0.9, 2.0, 7, 1.0, 0.0)),
                even = GroupParams(-1.0, -4.0),
                odd = GroupParams(0.0, -4.0),
                balance = 0.0,
                maxOrder = 9,
            )
            // Even and odd in balance, nothing standing out, a long shallow fall.
            R2R, R2R_NOS -> HarmonicProfile(
                h2Db = -6.0,
                h3Db = -6.0,
                algorithm = HarmonicAlgorithm.LINEAR,
                tails = HarmonicProfile.DEFAULT_TAILS + (HarmonicAlgorithm.LINEAR to TailParams(0.85, 1.2, 8, 1.0, -3.0)),
                even = GroupParams(0.0, -3.0),
                odd = GroupParams(0.0, -3.0),
                balance = 0.0,
                maxOrder = 12,
            )
            CUSTOM -> null
        }
}

/**
 * Everything the harmonic stage plays from: a profile, where it came from, how much of it, the
 * NOS roll-off, and a trim per band of the spectrum.
 */
public data class HarmonicSettings(
    val preset: HarmonicPreset = HarmonicPreset.TRIODE,
    val profile: HarmonicProfile = HarmonicPreset.TRIODE.profile!!,
    /** 0..100 %: the effect's amount, scaling every order's amplitude alike. */
    val intensityPercent: Int = 100,
    /** The NOS treble roll-off, a separate filter: see [NosRolloff]. */
    val nos: Boolean = false,
    /** Bass, midrange and treble, in dB (-15..0): how much each part of the spectrum feeds the harmonics. */
    val bandTrimsDb: List<Double> = listOf(0.0, 0.0, 0.0),
) {
    /**
     * The gain the processor applies to each order: the amplitude a full-scale tone's harmonic
     * of that order has. Intensity multiplies amplitudes, never decibels: at 50 % every
     * harmonic is 6 dB lower and the profile's proportions are untouched; at 0 % there are none.
     */
    public fun drive(): HarmonicDrive {
        val scale = intensityPercent.coerceIn(0, 100) / 100.0 * 10.0.pow(REFERENCE_DB / 20)
        val gains = profile.relativeAmplitudes()
        for (i in gains.indices) gains[i] *= scale
        return HarmonicDrive(gains, DoubleArray(BANDS) { 10.0.pow(bandTrimsDb.getOrElse(it) { 0.0 }.coerceIn(MIN_DB, 0.0) / 20) })
    }

    /** A copy with [change] made to the profile, marked as the listener's own. */
    public fun edit(change: (HarmonicProfile) -> HarmonicProfile): HarmonicSettings =
        copy(profile = change(profile), preset = HarmonicPreset.CUSTOM)

    /** A copy with [preset]'s profile and NOS setting; the amount and band trims stay. */
    public fun withPreset(preset: HarmonicPreset): HarmonicSettings {
        val profile = preset.profile ?: return copy(preset = HarmonicPreset.CUSTOM)
        return copy(preset = preset, profile = profile, nos = preset.nos)
    }

    public companion object {
        /** 0 dB on every harmonic control: a harmonic 30 dB (3 %) below a full-scale tone. */
        public const val REFERENCE_DB: Double = -30.0
        public const val MIN_DB: Double = -15.0
        public const val MAX_DB: Double = 0.0
        public const val STEP_DB: Double = 0.1
        public const val MIN_ORDER: Int = 3
        public const val MAX_ORDER: Int = 16
        public const val SLOPE_MAX_DB: Double = 6.0
        public const val SHAPE_MIN: Double = 0.2
        public const val SHAPE_MAX: Double = 5.0
        public const val BANDS: Int = 3

        /** A control value on the 0.1 dB grid and inside -15..0 dB. */
        public fun clampDb(value: Double): Double = ((value.coerceIn(MIN_DB, MAX_DB) / STEP_DB).roundToInt() * STEP_DB).let {
            // Avoids -0.0 and float dust such as -3.0000000000000004 in what is stored and shown.
            (it * 10).roundToInt() / 10.0 + 0.0
        }
    }
}

/** What [HarmonicProcessor] plays: a gain per order (index = order) and a gain per band. */
public class HarmonicDrive(public val gains: DoubleArray, public val bandGains: DoubleArray) {
    public val silent: Boolean get() = gains.all { it == 0.0 } || bandGains.all { it == 0.0 }

    public companion object {
        public val SILENT: HarmonicDrive = HarmonicDrive(DoubleArray(HarmonicSettings.MAX_ORDER + 1), DoubleArray(HarmonicSettings.BANDS) { 1.0 })
    }
}
