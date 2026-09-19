package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * "Where does it roll off?"
 *
 * Both ends are examined, because they fail differently: a low cut takes away weight, a high cut
 * takes away air, and learners are usually far better at one than the other. The candidate answers
 * are neighbours on a log grid whose spacing shrinks as the level rises, so the question stays
 * "how precisely" rather than "which half of the spectrum".
 */
public class BandwidthGenerator : ExerciseGenerator {

    private data class Params(
        /** Spacing between candidate answers, in octaves. */
        val spacingOctaves: Double,
        val candidates: Int,
        val slopeDbPerOctave: Int,
        val testLowCut: Boolean,
    )

    private val ladder = listOf(
        Params(2.0, 4, 48, false) to LevelSpec(0, "High cut, wide steps", "Two octaves apart, steep 48 dB/oct"),
        Params(1.0, 5, 48, false) to LevelSpec(1, "High cut, one octave", "Octave steps"),
        Params(1.0, 5, 24, false) to LevelSpec(2, "Gentler slope", "24 dB/oct is harder to place"),
        Params(2.0, 4, 48, true) to LevelSpec(3, "Low cut introduced", "Now the bottom end rolls off"),
        Params(1.0, 5, 24, true) to LevelSpec(4, "Either end", "Octave steps at either extreme"),
        Params(0.5, 5, 24, true) to LevelSpec(5, "Half-octave steps", "Candidates close together"),
        Params(0.5, 6, 12, true) to LevelSpec(6, "Shallow slope", "12 dB/oct, half-octave steps"),
        Params(0.33, 6, 12, true) to LevelSpec(7, "Third-octave steps", "As fine as the exercise goes"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.BANDWIDTH

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        val lowCutTrial = params.testLowCut && random.nextBoolean()

        val anchors = if (lowCutTrial) LOW_CUT_ANCHORS else HIGH_CUT_ANCHORS
        val base = anchors[random.nextInt(anchors.size)]
        val step = Math.pow(2.0, params.spacingOctaves)

        // Candidates straddle the true cutoff so it is never simply the lowest or highest option.
        val correctIndex = random.nextInt(params.candidates)
        val frequencies = (0 until params.candidates).map { i ->
            base * Math.pow(step, (i - correctIndex).toDouble())
        }

        val cutoff = frequencies[correctIndex]
        val spec = if (lowCutTrial) {
            ArtifactSpec.Bandwidth(lowCutHz = cutoff, highCutHz = 20000.0, slopeDbPerOctave = params.slopeDbPerOctave)
        } else {
            ArtifactSpec.Bandwidth(lowCutHz = 20.0, highCutHz = cutoff, slopeDbPerOctave = params.slopeDbPerOctave)
        }

        val choices = frequencies.mapIndexed { i, hz ->
            Choice(id = "f$i", label = formatHz(hz), ordinal = hz)
        }

        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = if (lowCutTrial) "Where is the low-frequency cutoff?" else "Where is the high-frequency cutoff?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "f$correctIndex",
            random = random,
        )
    }

    private fun formatHz(hz: Double): String =
        if (hz >= 1000) "${(hz / 100).roundToInt() / 10.0} kHz" else "${hz.roundToInt()} Hz"

    private companion object {
        val HIGH_CUT_ANCHORS = doubleArrayOf(2000.0, 3150.0, 5000.0, 8000.0, 12500.0)
        val LOW_CUT_ANCHORS = doubleArrayOf(40.0, 63.0, 100.0, 160.0, 250.0)
    }
}
