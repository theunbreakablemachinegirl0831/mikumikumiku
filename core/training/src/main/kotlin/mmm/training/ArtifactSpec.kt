package mmm.training

import mmm.dsp.Band
import mmm.dsp.AudioProcessor
import mmm.dsp.PassThroughProcessor
import mmm.dsp.ProcessorChain
import mmm.dsp.artifacts.BandBoostProcessor
import mmm.dsp.artifacts.BandwidthProcessor
import mmm.dsp.artifacts.CompressionProcessor
import mmm.dsp.artifacts.DistortionProcessor
import mmm.dsp.artifacts.DistortionType
import mmm.dsp.artifacts.ResonanceProcessor
import mmm.dsp.artifacts.ReverbProcessor
import mmm.dsp.artifacts.SpectralTiltProcessor

/**
 * A declarative description of what was done to a stimulus.
 *
 * The engine deals in specs rather than in live processor objects for three reasons: a spec can be
 * written to the progress log so a trial is reproducible months later, it can be rendered as the
 * post-answer explanation ("that was +9 dB at 500 Hz"), and the *same* spec can be instantiated
 * against a decoded file or against a captured stream - which is what lets the two playback modes
 * share one question bank.
 */
public sealed interface ArtifactSpec {

    /** Human-readable, shown after the learner answers. */
    public val description: String

    public fun createProcessor(): AudioProcessor

    /** The unprocessed reference. */
    public data object None : ArtifactSpec {
        override val description: String get() = "처리 없음"
        override fun createProcessor(): AudioProcessor = PassThroughProcessor
    }

    public data class BandBoost(val band: Band, val gainDb: Double) : ArtifactSpec {
        override val description: String
            get() = "${band.label} 대역 ${if (gainDb >= 0) "+" else ""}${fmt(gainDb)} dB"
        override fun createProcessor(): AudioProcessor = BandBoostProcessor(band, gainDb)
    }

    public data class Resonance(
        val frequencyHz: Double,
        val q: Double,
        val gainDb: Double,
    ) : ArtifactSpec {
        override val description: String
            get() = "${fmt(frequencyHz)} Hz 레조넌스, Q ${fmt(q)}, +${fmt(gainDb)} dB"
        override fun createProcessor(): AudioProcessor = ResonanceProcessor(frequencyHz, q, gainDb)
    }

    public data class Bandwidth(
        val lowCutHz: Double,
        val highCutHz: Double,
        val slopeDbPerOctave: Int = 24,
    ) : ArtifactSpec {
        override val description: String
            get() = buildString {
                append("대역 제한 ")
                append(fmt(lowCutHz)).append(" Hz - ")
                append(fmt(highCutHz)).append(" Hz")
                append(" (").append(slopeDbPerOctave).append(" dB/oct)")
            }
        override fun createProcessor(): AudioProcessor =
            BandwidthProcessor(highCutHz, lowCutHz, slopeDbPerOctave)
    }

    public data class Distortion(val type: DistortionType, val amount: Double) : ArtifactSpec {
        override val description: String
            get() = "${type.displayName}, 강도 ${fmt(amount * 100)} %"
        override fun createProcessor(): AudioProcessor = DistortionProcessor(type, amount)
    }

    public data class Compression(
        val thresholdDb: Double,
        val ratio: Double,
        val attackMs: Double,
        val releaseMs: Double,
        val makeupGainDb: Double = 0.0,
    ) : ArtifactSpec {
        override val description: String
            get() = "${fmt(thresholdDb)} dB 이상에서 ${fmt(ratio)}:1 " +
                "(어택 ${fmt(attackMs)} ms, 릴리즈 ${fmt(releaseMs)} ms)"
        override fun createProcessor(): AudioProcessor =
            CompressionProcessor(thresholdDb, ratio, attackMs, releaseMs, makeupGainDb = makeupGainDb)
    }

    public data class Reverb(val decaySeconds: Double, val mix: Double) : ArtifactSpec {
        override val description: String
            get() = "리버브 ${fmt(decaySeconds)}초, 웻 ${fmt(mix * 100)} %"
        override fun createProcessor(): AudioProcessor = ReverbProcessor(decaySeconds, mix)
    }

    public data class SpectralTilt(val tiltDb: Double, val pivotHz: Double = 1000.0) : ArtifactSpec {
        override val description: String
            get() = "${if (tiltDb >= 0) "밝게" else "어둡게"} ${fmt(kotlin.math.abs(tiltDb))} dB 기울임"
        override fun createProcessor(): AudioProcessor = SpectralTiltProcessor(tiltDb, pivotHz)
    }

    public data class Chain(val specs: List<ArtifactSpec>) : ArtifactSpec {
        override val description: String get() = specs.joinToString(" + ") { it.description }
        override fun createProcessor(): AudioProcessor =
            ProcessorChain(specs.map { it.createProcessor() })
    }

    public companion object {
        internal fun fmt(value: Double): String {
            val rounded = (value * 100).toInt() / 100.0
            return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString()
            else rounded.toString().trimEnd('0').trimEnd('.')
        }
    }
}
