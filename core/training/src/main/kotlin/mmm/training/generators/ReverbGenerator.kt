package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.FilterOverrides
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
            LevelSpec(0, "방 · 홀 · 성당", "잔향 시간 차이가 크다"),
        Params(listOf(0.4, 0.9, 2.0, 4.0), 0.28, false) to
            LevelSpec(1, "잔향 시간 4가지", "여전히 뚜렷이 구분된다"),
        Params(listOf(0.5, 0.8, 1.3, 2.0, 3.2), 0.25, false) to
            LevelSpec(2, "잔향 시간 5가지", "대략 반 단계 간격"),
        Params(listOf(0.6, 0.85, 1.2, 1.7, 2.4), 0.20, false) to
            LevelSpec(3, "더 가까운 잔향 시간", "더 작은 리버브, 더 좁은 간격"),
        Params(listOf(1.2), 0.0, true, listOf(0.05, 0.15, 0.30, 0.50)) to
            LevelSpec(4, "얼마나 젖었나?", "1.2초 고정, 양을 판단한다"),
        Params(listOf(1.2), 0.0, true, listOf(0.04, 0.08, 0.15, 0.25, 0.40)) to
            LevelSpec(5, "웻 레벨 · 세밀", "5단계, 거의 드라이까지"),
        Params(listOf(0.7, 0.9, 1.1, 1.4, 1.8), 0.15, false) to
            LevelSpec(6, "잔향 · 작고 촘촘", "웻 15 %, 1/4 단계 간격"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.REVERB

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random, overrides: FilterOverrides): Question {
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
                prompt = "리버브(Reverb)가 얼마나 걸렸나?",
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
            Choice(id = "d$i", label = "${d}초", ordinal = d)
        }
        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "잔향 시간(Decay)은 얼마나 긴가?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "d$index",
            random = random,
        )
    }
}
