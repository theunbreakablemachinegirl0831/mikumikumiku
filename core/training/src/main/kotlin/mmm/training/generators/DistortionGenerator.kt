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
        Params(0.85, 2, false, obviousTypes) to LevelSpec(0, "심한 왜곡", "강하게 걸림, 보기 2개"),
        Params(0.6, 2, false, obviousTypes) to LevelSpec(1, "강함", "대부분의 음원에서 여전히 뚜렷하다"),
        Params(0.45, 3, false, allTypes) to LevelSpec(2, "보통", "보기 3개, 모든 종류"),
        Params(0.32, 3, false, allTypes) to LevelSpec(3, "약함", "미묘해지기 시작한다"),
        Params(0.22, 3, false, allTypes) to LevelSpec(4, "역치 근처", "음원을 잘 만나야 들린다"),
        Params(0.7, 3, true, obviousTypes) to LevelSpec(5, "성격 맞히기", "강하게 걸지만 이제 종류를 맞힌다"),
        Params(0.5, 3, true, allTypes) to LevelSpec(6, "성격 · 모든 종류", "다섯 가지를 구별한다"),
        Params(0.35, 3, true, allTypes) to LevelSpec(7, "성격 · 미묘함", "약하게 걸린 상태에서 종류 식별"),
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
                prompt = "왜곡(Distortion)된 것은?",
                artifact = spec,
                alternatives = params.alternatives,
                random = random,
            )
        }

        val choices = params.types.map { Choice(id = it.name, label = it.displayName) }
        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "어떤 종류의 왜곡인가?",
            artifact = spec,
            choices = choices,
            correctChoiceId = type.name,
            random = random,
        )
    }
}
