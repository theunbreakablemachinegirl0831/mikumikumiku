package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter

/**
 * Broad spectral balance: a see-saw around [pivotHz]. Positive [tiltDb] means bright (top up,
 * bottom down), negative means dark. This is the "is it warm or thin" family of questions, and it
 * is level-neutral at the pivot by construction.
 */
public class SpectralTiltProcessor(
    tiltDb: Double = 0.0,
    pivotHz: Double = 1000.0,
) : AudioProcessor {

    private val lowShelf = BiquadFilter()
    private val highShelf = BiquadFilter()
    private var format: AudioFormat? = null

    public var tiltDb: Double = tiltDb
        set(value) { field = value; updateCoefficients() }

    public var pivotHz: Double = pivotHz
        set(value) { field = value; updateCoefficients() }

    override fun prepare(format: AudioFormat) {
        this.format = format
        lowShelf.prepare(format)
        highShelf.prepare(format)
        updateCoefficients()
    }

    override fun process(buffer: AudioBuffer) {
        lowShelf.process(buffer)
        highShelf.process(buffer)
    }

    override fun reset() {
        lowShelf.reset()
        highShelf.reset()
    }

    private fun updateCoefficients() {
        val fmt = format ?: return
        val half = tiltDb / 2.0
        lowShelf.coefficients = BiquadDesign.lowShelf(pivotHz / 4.0, 0.7, -half, fmt.sampleRate)
        highShelf.coefficients = BiquadDesign.highShelf(pivotHz * 4.0, 0.7, half, fmt.sampleRate)
    }
}
