package mmm.eq

/**
 * A rule that turns hearing thresholds into the gain a listener should be given.
 *
 * These are the linear prescriptions whose formulas are published in full: a threshold in, a gain
 * out, the same at every loudness. The current clinical ones - NAL-NL2 and DSL v5 - are not here,
 * and that is deliberate rather than an omission: both prescribe a different gain for quiet and
 * loud sound, which needs a multi-band compressor rather than an EQ; both need thresholds in
 * absolute dB HL, which a phone and an earphone cannot give; and NAL-NL2 exists only as licensed
 * software, DSL v5 as tables and software, neither as a formula that could be written out here.
 * NAL-R is the linear rule the NAL-NL family grew from, and is the closest thing an EQ can honour.
 */
public enum class HearingRule(public val label: String, public val summary: String) {
    NAL_R(
        "NAL-R",
        "호주 국립음향연구소의 선형 처방(Byrne & Dillon, 1986). 손실의 31%를 주파수별로 보상하고, 500·1k·2k Hz 평균 손실의 15%를 전체에 더한다. NAL-NL 계열의 바탕이 된 식이다.",
    ),
    POGO(
        "POGO",
        "McCandless & Lyregaard(1983). 손실의 절반을 보상하되 250 Hz는 10 dB, 500 Hz는 5 dB 덜 준다.",
    ),
    HALF_GAIN(
        "1/2 이득",
        "Lybarger의 반이득 법칙: 모든 주파수에서 손실의 절반을 보상한다. 가장 오래되고 단순한 기준이다.",
    ),
    THIRD_GAIN(
        "1/3 이득",
        "Libby의 1/3 이득 법칙: 손실의 3분의 1만 보상한다. 가벼운 손실이나 음악 감상에 무난하다.",
    ),
}

/**
 * Insertion gain from an audiogram, by one of the published linear rules.
 *
 * Every rule here is linear in the thresholds, so an audiogram that is only known relative to its
 * own 1 kHz - which is all an uncalibrated test gives - yields the right shape: the unknown
 * offset only adds the same gain everywhere, and a level change is not an EQ.
 */
public object HearingPrescription {

    /** The audiometric frequencies the rules are defined at. */
    public val FREQUENCIES: List<Double> = listOf(250.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0)

    /**
     * NAL-R's frequency constants. 250 and 500 Hz are as the formula is reproduced in fitting
     * equipment manuals; the rest are quoted from memory of the same table and should be checked
     * against Byrne & Dillon (1986) before being cited. 8 kHz is not part of the rule and takes
     * the 6 kHz value.
     */
    private val NAL_R_SHAPE: Map<Double, Double> = mapOf(
        250.0 to -17.0,
        500.0 to -8.0,
        1000.0 to 1.0,
        2000.0 to -1.0,
        3000.0 to -2.0,
        4000.0 to -2.0,
        6000.0 to -2.0,
        8000.0 to -2.0,
    )

    /** POGO gives less in the bass, where amplified speech masks itself, and nothing extra elsewhere. */
    private val POGO_SHAPE: Map<Double, Double> = mapOf(250.0 to -10.0, 500.0 to -5.0)

    /**
     * @param thresholds hearing level in dB at audiometric frequencies; absolute or relative
     * @param speechShape include the rule's fixed frequency shaping, which is tuned for speech
     *   and independent of the listener - left out, only what the listener's own loss asks for
     *   remains, which is what suits music
     * @param strength scales the result: 1 is the rule as written
     * @return the gain at each measured frequency, or null when there is nothing to work from
     */
    public fun insertionGain(
        rule: HearingRule,
        thresholds: Map<Double, Double>,
        speechShape: Boolean = false,
        strength: Double = 1.0,
    ): FrequencyResponse? {
        val measured = FREQUENCIES.filter { it in thresholds }
        if (measured.size < 2) return null
        fun level(frequency: Double): Double = thresholds[frequency]
            ?: FrequencyResponse(measured, measured.map { thresholds.getValue(it) }).at(frequency)

        val gains = measured.map { frequency ->
            val loss = thresholds.getValue(frequency)
            val gain = when (rule) {
                HearingRule.NAL_R -> {
                    val overall = 0.05 * (level(500.0) + level(1000.0) + level(2000.0))
                    overall + 0.31 * loss + if (speechShape) NAL_R_SHAPE[frequency] ?: 0.0 else 0.0
                }
                HearingRule.POGO -> 0.5 * loss + if (speechShape) POGO_SHAPE[frequency] ?: 0.0 else 0.0
                HearingRule.HALF_GAIN -> 0.5 * loss
                HearingRule.THIRD_GAIN -> loss / 3.0
            }
            gain * strength
        }
        return FrequencyResponse(measured, gains)
    }
}
