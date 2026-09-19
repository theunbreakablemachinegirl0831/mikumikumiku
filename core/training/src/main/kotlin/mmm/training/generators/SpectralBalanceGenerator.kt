package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import kotlin.random.Random

/**
 * Broad tonal balance - the "is this bright or dark, and by how much" judgement that underlies
 * every loudspeaker and headphone preference rating.
 *
 * Zero tilt stays in the answer list at every level on purpose. Without it the exercise has no way
 * to catch a listener who has learned to always report *some* colouration.
 */
public class SpectralBalanceGenerator : ExerciseGenerator {

    private data class Params(val tilts: List<Double>)

    private val ladder = listOf(
        Params(listOf(-12.0, 0.0, 12.0)) to LevelSpec(0, "Dark, flat or bright", "12 dB either way"),
        Params(listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) to LevelSpec(1, "Five steps", "6 dB steps"),
        Params(listOf(-9.0, -4.5, 0.0, 4.5, 9.0)) to LevelSpec(2, "Narrower range", "4.5 dB steps"),
        Params(listOf(-6.0, -3.0, 0.0, 3.0, 6.0)) to LevelSpec(3, "3 dB steps", "The usual audible limit on music"),
        Params(listOf(-4.0, -2.0, 0.0, 2.0, 4.0)) to LevelSpec(4, "2 dB steps", "Subtle"),
        Params(listOf(-3.0, -1.5, 0.0, 1.5, 3.0)) to LevelSpec(5, "1.5 dB steps", "Very subtle"),
        Params(listOf(-2.0, -1.0, 0.0, 1.0, 2.0)) to LevelSpec(6, "1 dB steps", "Expert range"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.SPECTRAL_BALANCE

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        val index = random.nextInt(params.tilts.size)
        val tilt = params.tilts[index]
        val spec: ArtifactSpec =
            if (tilt == 0.0) ArtifactSpec.None else ArtifactSpec.SpectralTilt(tilt)

        val choices = params.tilts.mapIndexed { i, t ->
            val label = when {
                t > 0 -> "+$t dB bright"
                t < 0 -> "${-t} dB dark"
                else -> "Flat"
            }
            Choice(id = "t$i", label = label, ordinal = t)
        }

        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "How is the balance tilted?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "t$index",
            random = random,
        )
    }
}
