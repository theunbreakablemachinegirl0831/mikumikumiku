package mmm.dsp.artifacts

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.Band
import mmm.dsp.BiquadDesign
import mmm.dsp.BiquadFilter

/**
 * The Band ID artifact: one band of the grid is boosted (or cut) and the learner names it.
 *
 * By default the filter Q is derived from the band's own width, so that a 1/3-octave grid really
 * does get a 1/3-octave bump - otherwise a wide bump on a fine grid would have several "correct"
 * answers. [q] overrides that for listeners who want a different shape; widening it past the grid
 * spacing makes neighbouring bands rise too, which is their call to make.
 */
public class BandBoostProcessor(
    band: Band,
    gainDb: Double = 12.0,
    q: Double? = null,
) : AudioProcessor {

    private val filter = BiquadFilter()
    private var format: AudioFormat? = null

    public var band: Band = band
        set(value) {
            field = value
            updateCoefficients()
        }

    public var gainDb: Double = gainDb
        set(value) {
            field = value
            updateCoefficients()
        }

    /** Explicit Q, or null to match the band's width. */
    public var q: Double? = q
        set(value) {
            field = value
            updateCoefficients()
        }

    /** The Q actually in use. */
    public val effectiveQ: Double
        get() = q ?: BiquadDesign.qForBandwidthOctaves(band.bandwidthOctaves)

    override fun prepare(format: AudioFormat) {
        this.format = format
        filter.prepare(format)
        updateCoefficients()
    }

    override fun process(buffer: AudioBuffer): Unit = filter.process(buffer)

    override fun reset(): Unit = filter.reset()

    private fun updateCoefficients() {
        val fmt = format ?: return
        filter.coefficients = BiquadDesign.peaking(
            frequency = band.centerHz,
            q = effectiveQ,
            gainDb = gainDb,
            sampleRate = fmt.sampleRate,
        )
    }
}
