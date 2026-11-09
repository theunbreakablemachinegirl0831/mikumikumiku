package mmm.eq

import kotlin.math.log10

/**
 * Turns levels the app sends into levels at the eardrum, using an earphone's published measurement.
 *
 * A hearing test run through an earphone measures the ear and the earphone together: a threshold
 * that looks 6 dB worse at 3 kHz may just be an earphone that is 6 dB quiet at 3 kHz. The
 * measurement (made on a B&K 5128, whose ear canal and eardrum stand in for an average listener's)
 * says how loud the earphone is at each frequency for the same input, so adding it puts every
 * frequency on one footing.
 *
 * Without [sensitivity] the result is relative: "dB at the eardrum, with 1 kHz as reference", which
 * is all an EQ needs. With it, an estimate of absolute level - rough, because unit variation and
 * fit add a few dB either way.
 *
 * @param measured the earphone's raw measured response (any level offset; only its shape is used)
 */
public class EarphoneCalibration(
    measured: FrequencyResponse,
    private val sensitivity: Sensitivity? = null,
) {
    private val shape = measured.smooth(SMOOTHING).let { it.shifted(-it.at(REFERENCE_HZ)) }

    /** How much louder (+) or quieter (-) the earphone is at [frequency] than at 1 kHz. */
    public fun relativeGainDb(frequency: Double): Double = shape.at(frequency)

    /** A level sent at [frequency], expressed as the 1 kHz level that reaches the eardrum equally loud. */
    public fun eardrumRelativeDb(levelDbfs: Double, frequency: Double): Double =
        levelDbfs + relativeGainDb(frequency)

    /**
     * Estimated sound pressure at the eardrum for a sine at [levelDbfs] RMS, or null without a
     * sensitivity. [fullScaleVolts] is the DAC's RMS output at 0 dBFS with its volume where it
     * will stay for the test.
     */
    public fun eardrumSplDb(levelDbfs: Double, frequency: Double, fullScaleVolts: Double): Double? {
        val s = sensitivity ?: return null
        val voltsDb = 20 * log10(fullScaleVolts) + levelDbfs
        return s.dbSplPerVolt + voltsDb + relativeGainDb(frequency)
    }

    /** An earphone's sensitivity at 1 kHz, as the spec sheet gives it. */
    public data class Sensitivity(val dbSplPerVolt: Double) {
        public companion object {
            /** Converts a dB/mW spec using the earphone's impedance: P = V^2 / R. */
            public fun fromDbPerMilliwatt(dbSplPerMilliwatt: Double, impedanceOhms: Double): Sensitivity =
                Sensitivity(dbSplPerMilliwatt + 10 * log10(1000.0 / impedanceOhms))
        }
    }

    private companion object {
        const val REFERENCE_HZ = 1000.0
        const val SMOOTHING = 6
    }
}
