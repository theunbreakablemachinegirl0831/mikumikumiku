package mmm.training.audiometry

public enum class Ear(public val label: String) {
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
}

/** One measured point: the threshold for one ear at one frequency, in dBFS. */
public data class ThresholdPoint(
    val ear: Ear,
    val frequencyHz: Double,
    /** Null when the tone was not heard reliably even at the top of the range. */
    val thresholdDbfs: Double?,
    val presentations: Int,
)

/** A finished test: every point, plus how trustworthy the responses were. */
public data class AudiogramResult(
    val points: List<ThresholdPoint>,
    /** Presses with no tone playing, out of [catchTrials] silent presentations. */
    val falseAlarms: Int,
    val catchTrials: Int,
    /** Difference between the first 1 kHz threshold and its retest, per ear; large means unreliable. */
    val retestDifferenceDb: Map<Ear, Double>,
    val earphoneName: String?,
    val timestampMillis: Long,
) {
    public fun point(ear: Ear, frequencyHz: Double): ThresholdPoint? =
        points.firstOrNull { it.ear == ear && it.frequencyHz == frequencyHz }

    /** A retest more than 10 dB off, or presses in silence, say the result should not be leaned on. */
    public val reliable: Boolean
        get() = retestDifferenceDb.values.all { it <= 10.0 } && (catchTrials == 0 || falseAlarms * 4 <= catchTrials)
}

/**
 * The order of a test, after clinical practice: 1 kHz first because it is the easiest to hear
 * and anchors the listener; up through the high frequencies; 1 kHz again as a reliability check;
 * then down to the lows. One ear completely, then the other.
 */
public object AudiometryProtocol {
    public val FREQUENCIES: List<Double> = listOf(1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0, 1000.0, 500.0, 250.0)

    /** Every audiogram frequency once, low to high, for drawing. */
    public val CHART_FREQUENCIES: List<Double> = FREQUENCIES.distinct().sorted()

    public const val RETEST_FREQUENCY_HZ: Double = 1000.0

    /** One in this many presentations plays nothing, to catch pressing on rhythm instead of on sound. */
    public const val CATCH_TRIAL_EVERY: Int = 8
}
