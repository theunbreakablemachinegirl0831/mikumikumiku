package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadCoefficients
import mmm.dsp.BiquadCascade
import mmm.dsp.BiquadDesign

/**
 * Bandwidth limitation: rolls the top and/or bottom off the signal so the learner has to judge
 * where the band edge sits. This is the exercise that separates "sounds thin" from "sounds dull".
 *
 * [slopeDbPerOctave] is realised as cascaded Butterworth biquad sections, 12 dB/oct each.
 */
public class BandwidthProcessor(
    highCutHz: Double = 20000.0,
    lowCutHz: Double = 20.0,
    slopeDbPerOctave: Int = 24,
) : AudioProcessor {

    private var format: AudioFormat? = null
    private var lowPass: BiquadCascade? = null
    private var highPass: BiquadCascade? = null

    public var highCutHz: Double = highCutHz
        set(value) { field = value; updateCoefficients() }

    public var lowCutHz: Double = lowCutHz
        set(value) { field = value; updateCoefficients() }

    /** Rounded down to a multiple of 12 dB/oct, minimum 12. */
    public var slopeDbPerOctave: Int = slopeDbPerOctave
        set(value) { field = value; rebuild() }

    override fun prepare(format: AudioFormat) {
        this.format = format
        rebuild()
    }

    override fun process(buffer: AudioBuffer) {
        highPass?.process(buffer)
        lowPass?.process(buffer)
    }

    override fun reset() {
        highPass?.reset()
        lowPass?.reset()
    }

    private fun rebuild() {
        val fmt = format ?: return
        val sections = (slopeDbPerOctave / 12).coerceIn(1, 4)
        lowPass = BiquadCascade(sections, fmt.channels).also { it.prepare(fmt) }
        highPass = BiquadCascade(sections, fmt.channels).also { it.prepare(fmt) }
        updateCoefficients()
    }

    private fun updateCoefficients() {
        val fmt = format ?: return
        val lp = lowPass ?: return
        val hp = highPass ?: return
        val sections = lp.stages.size

        val lpCoeffs = if (highCutHz >= fmt.nyquist * 0.99) {
            BiquadCoefficients.IDENTITY
        } else {
            null
        }
        butterworthQs(sections).forEachIndexed { i, q ->
            lp.setCoefficients(i, lpCoeffs ?: BiquadDesign.lowPass(highCutHz, q, fmt.sampleRate))
        }

        val hpCoeffs = if (lowCutHz <= 1.0) BiquadCoefficients.IDENTITY else null
        butterworthQs(sections).forEachIndexed { i, q ->
            hp.setCoefficients(i, hpCoeffs ?: BiquadDesign.highPass(lowCutHz, q, fmt.sampleRate))
        }
    }

    /** Pole Qs for a cascade of [sections] biquads forming a Butterworth of order `2*sections`. */
    private fun butterworthQs(sections: Int): List<Double> {
        val order = sections * 2
        return (0 until sections).map { k ->
            val theta = kotlin.math.PI * (2.0 * k + 1.0) / (2.0 * order)
            1.0 / (2.0 * kotlin.math.cos(theta))
        }
    }
}
