package mmm.training

import mmm.dsp.SpectrumProfile
import kotlin.math.roundToInt

/** A stretch of the spectrum to practise in. */
public data class FrequencyRange(val lowHz: Double, val highHz: Double) {
    init {
        require(lowHz > 0 && highHz > lowHz) { "range must run upwards from above 0 Hz" }
    }

    public val label: String get() = "${hz(lowHz)}–${hz(highHz)}"

    public companion object {
        public const val MIN_HZ: Double = 20.0
        public const val MAX_HZ: Double = 20000.0

        /** The named ranges engineers talk in. They overlap, as the words do. */
        public val PRESETS: List<Pair<String, FrequencyRange>> = listOf(
            "저역 (Bass)" to FrequencyRange(31.5, 250.0),
            "중저역 (Low mids)" to FrequencyRange(200.0, 800.0),
            "중역 (Mids)" to FrequencyRange(500.0, 2000.0),
            "중고역 (Upper mids)" to FrequencyRange(1600.0, 5000.0),
            "고역 (Treble)" to FrequencyRange(4000.0, 16000.0),
        )

        private fun hz(value: Double): String = when {
            value >= 1000 -> {
                val k = (value / 100).roundToInt() / 10.0
                (if (k == kotlin.math.floor(k)) k.toInt().toString() else k.toString()) + " kHz"
            }
            else -> "${value.roundToInt()} Hz"
        }
    }
}

/**
 * Where questions should be aimed: inside the listener's chosen [range], and only where the audio
 * being trained on actually has something to hear ([spectrum]). Either may be absent - pink noise
 * fills every band, and live audio changes too fast to measure in advance.
 */
public data class PracticeFocus(
    val range: FrequencyRange? = null,
    val spectrum: SpectrumProfile? = null,
) {
    public fun isUsable(lowHz: Double, highHz: Double): Boolean = spectrum?.isUsable(lowHz, highHz) ?: true

    public companion object {
        public val ANY: PracticeFocus = PracticeFocus()

        /** Families whose questions are about a place in the spectrum, and so can be focused. */
        public val APPLIES_TO: Set<ExerciseFamily> = setOf(ExerciseFamily.BAND_ID, ExerciseFamily.RESONANCE)
    }
}
