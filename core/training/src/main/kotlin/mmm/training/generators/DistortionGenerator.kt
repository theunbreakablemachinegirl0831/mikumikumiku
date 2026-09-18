package mmm.training.generators

import mmm.dsp.artifacts.DistortionType
import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import kotlin.random.Random

/**
 * Distortion training in two phases: first hear *that* something is non-linear, then hear *what
 * kind*. Character identification is deliberately gated behind detection - asking someone to tell
 * crossover from soft clipping before they can reliably hear either just teaches them to guess.
 */
public class DistortionGenerator : ExerciseGenerator {

    private data class Params(
        val amount: Double,
        val alternatives: Int,
        /** When true, the trial asks which *kind* of distortion it is. */
        val identifyType: Boolean,
        val types: List<DistortionType>,
    )

    private val allTypes = DistortionType.entries.toList()
    private val obviousTypes = listOf(DistortionType.HARD_CLIP, DistortionType.SOFT_CLIP, DistortionType.CROSSOVER)

    private val ladder = listOf(
        Params(0.85, 2, false, obviousTypes) to LevelSpec(0, "Gross distortion", "Heavily driven, two alternatives"),
        Params(0.6, 2, false, obviousTypes) to LevelSpec(1, "Strong", "Still obvious on most material"),
        Params(0.45, 3, false, allTypes) to LevelSpec(2, "Moderate", "Three alternatives, all types"),
        Params(0.32, 3, false, allTypes) to LevelSpec(3, "Light", "Getting subtle"),
        Params(0.22, 3, false, allTypes) to LevelSpec(4, "Near threshold", "Audible only on the right material"),
        Params(0.7, 3, true, obviousTypes) to LevelSpec(5, "Naming the character", "Strong, but now identify the type"),
        Params(0.5, 3, true, allTypes) to LevelSpec(6, "Character, all types", "Five kinds to tell apart"),
        Params(0.35, 3, true, allTypes) to LevelSpec(7, "Character, subtle", "Type identification at low drive"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.DISTORTION

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        val type = params.types[random.nextInt(params.types.size)]
        val spec = ArtifactSpec.Distortion(type, params.amount)

        if (!params.identifyType) {
            return GeneratorSupport.forcedChoice(
                family = family,
                level = level,
                prompt = "Which one is distorted?",
                artifact = spec,
                alternatives = params.alternatives,
                random = random,
            )
        }

        val choices = params.types.map { Choice(id = it.name, label = it.displayName) }
        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "What kind of distortion is this?",
            artifact = spec,
            choices = choices,
            correctChoiceId = type.name,
            random = random,
        )
    }
}
