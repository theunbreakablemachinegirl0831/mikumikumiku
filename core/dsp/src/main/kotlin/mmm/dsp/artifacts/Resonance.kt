package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter

/**
 * A narrow high-Q peak - the "ringing"/"honky" colouration that loudspeaker cabinets and room
 * modes produce, and the artifact the resonance-detection exercises train.
 *
 * Distinct from [BandBoostProcessor] on purpose: Band ID is about *where* a broad tilt sits,
 * resonance detection is about hearing a narrow ring at a *small* gain.
 */
public class ResonanceProcessor(
    frequencyHz: Double = 1000.0,
    q: Double = 12.0,
    gainDb: Double = 6.0,
) : AudioProcessor {

    private val filter = BiquadFilter()
    private var format: AudioFormat? = null

    public var frequencyHz: Double = frequencyHz
        set(value) { field = value; updateCoefficients() }

    /** Higher Q rings longer and is harder to hear on sparse material. */
    public var q: Double = q
        set(value) { field = value; updateCoefficients() }

    public var gainDb: Double = gainDb
        set(value) { field = value; updateCoefficients() }

    override fun prepare(format: AudioFormat) {
        this.format = format
        filter.prepare(format)
        updateCoefficients()
    }

    override fun process(buffer: AudioBuffer): Unit = filter.process(buffer)
    override fun reset(): Unit = filter.reset()

    private fun updateCoefficients() {
        val fmt = format ?: return
        filter.coefficients = BiquadDesign.peaking(frequencyHz, q, gainDb, fmt.sampleRate)
    }
}
