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
 * Reverberation: how long the tail is, and how much of it there is.
 *
 * Decay time and wet level are trained separately because they are confusable - a short loud
 * reverb and a long quiet one are easy to mix up, and a listener who has only ever adjusted one
 * knob learns to hear "amount of effect" rather than either parameter.
 */
public class ReverbGenerator : ExerciseGenerator {

    private data class Params(
        val decays: List<Double>,
        val mix: Double,
        /** When true the decay is held fixed and the wet level is the variable. */
        val askAboutMix: Boolean,
        val mixes: List<Double> = emptyList(),
    )

    private val ladder = listOf(
        Params(listOf(0.3, 1.2, 4.0), 0.30, false) to
            LevelSpec(0, "Room, hall or cathedral", "Decay times far apart"),
        Params(listOf(0.4, 0.9, 2.0, 4.0), 0.28, false) to
            LevelSpec(1, "Four decay times", "Still clearly separated"),
        Params(listOf(0.5, 0.8, 1.3, 2.0, 3.2), 0.25, false) to
            LevelSpec(2, "Five decay times", "Roughly half-steps apart"),
        Params(listOf(0.6, 0.85, 1.2, 1.7, 2.4), 0.20, false) to
            LevelSpec(3, "Closer decay times", "Quieter reverb, closer spacing"),
        Params(listOf(1.2), 0.0, true, listOf(0.05, 0.15, 0.30, 0.50)) to
            LevelSpec(4, "How wet?", "Fixed 1.2 s decay, judge the amount"),
        Params(listOf(1.2), 0.0, true, listOf(0.04, 0.08, 0.15, 0.25, 0.40)) to
            LevelSpec(5, "Wet level, fine", "Five levels, down to nearly dry"),
        Params(listOf(0.7, 0.9, 1.1, 1.4, 1.8), 0.15, false) to
            LevelSpec(6, "Decay, quiet and close", "15 % wet, quarter-step decay spacing"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.REVERB

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first

        if (params.askAboutMix) {
            val index = random.nextInt(params.mixes.size)
            val mix = params.mixes[index]
            val spec = ArtifactSpec.Reverb(params.decays.first(), mix)
            val choices = params.mixes.mapIndexed { i, m ->
                Choice(id = "m$i", label = "${(m * 100).toInt()} %", ordinal = m)
            }
            return GeneratorSupport.identify(
                family = family,
                level = level,
                prompt = "How much reverb was added?",
                artifact = spec,
                choices = choices,
                correctChoiceId = "m$index",
                random = random,
            )
        }

        val index = random.nextInt(params.decays.size)
        val decay = params.decays[index]
        val spec = ArtifactSpec.Reverb(decay, params.mix)
        val choices = params.decays.mapIndexed { i, d ->
            Choice(id = "d$i", label = "$d s", ordinal = d)
        }
        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "How long is the decay?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "d$index",
            random = random,
        )
    }
}
