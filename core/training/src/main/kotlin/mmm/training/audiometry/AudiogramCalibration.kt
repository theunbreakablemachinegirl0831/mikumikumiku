package mmm.training.audiometry

/**
 * Turns thresholds measured in dBFS into an audiogram shape: hearing level relative to the
 * listener's own 1 kHz.
 *
 * Two corrections make the dBFS numbers comparable across frequency. The earphone's measured
 * response (from its B&K 5128 measurement) says how much louder it plays each frequency than
 * 1 kHz for the same input. And normal hearing is itself not flat in sound pressure - it takes
 * more pressure to hear 250 Hz than 1 kHz - which the reference threshold curve accounts for;
 * subtracting it is what turns pressure into hearing level.
 *
 * What stays unknown is the absolute offset: without a calibrated chain the 1 kHz threshold
 * itself is not known in dB HL, so the result is anchored there and reads as "how much worse
 * (+) or better (-) than at 1 kHz". That is what an EQ needs, and it shows the shapes that
 * matter - a high-frequency slope, a 4 kHz notch, one ear worse than the other.
 */
public object AudiogramCalibration {

    /**
     * Reference equivalent threshold sound pressure levels for insert earphones on an occluded-ear
     * simulator (ISO 389-2, ER-3A type), re-expressed relative to 1 kHz. In-ear measurements on
     * the 5128 sit closest to that coupling of the reference conditions available. Values to be
     * checked against the standard before being quoted.
     */
    public val REFERENCE_RELATIVE_DB: Map<Double, Double> = mapOf(
        250.0 to 14.0,
        500.0 to 5.5,
        1000.0 to 0.0,
        2000.0 to 3.0,
        3000.0 to 3.5,
        4000.0 to 5.5,
        6000.0 to 2.0,
        8000.0 to 0.0,
    )

    /**
     * Relative hearing level for every measured point, anchored at the mean of both ears' 1 kHz
     * thresholds so a difference between the ears survives.
     *
     * @param earphoneGainDb how much louder the earphone plays a frequency than 1 kHz; zero
     *   everywhere when no measurement is available, which leaves the earphone in the result
     */
    public fun relativeHearingLevels(
        result: AudiogramResult,
        earphoneGainDb: (Double) -> Double,
    ): Map<Pair<Ear, Double>, Double> {
        val anchors = Ear.entries.mapNotNull { ear ->
            result.points.firstOrNull { it.ear == ear && it.frequencyHz == 1000.0 }?.thresholdDbfs
        }
        if (anchors.isEmpty()) return emptyMap()
        val anchor = anchors.average() + earphoneGainDb(1000.0)

        return result.points
            .filter { it.thresholdDbfs != null }
            .groupBy { it.ear to it.frequencyHz }
            .mapValues { (key, points) ->
                // The 1 kHz retest is averaged with the first measurement.
                val threshold = points.mapNotNull { it.thresholdDbfs }.average()
                val frequency = key.second
                threshold + earphoneGainDb(frequency) - (REFERENCE_RELATIVE_DB[frequency] ?: 0.0) - anchor
            }
    }
}
