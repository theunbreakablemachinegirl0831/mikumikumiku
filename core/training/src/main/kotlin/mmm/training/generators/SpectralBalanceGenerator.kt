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
        Params(listOf(-12.0, 0.0, 12.0)) to LevelSpec(0, "어둡게 · 평탄 · 밝게", "양쪽 12 dB"),
        Params(listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) to LevelSpec(1, "5단계", "6 dB 간격"),
        Params(listOf(-9.0, -4.5, 0.0, 4.5, 9.0)) to LevelSpec(2, "좁은 범위", "4.5 dB 간격"),
        Params(listOf(-6.0, -3.0, 0.0, 3.0, 6.0)) to LevelSpec(3, "3 dB 간격", "음악에서 흔한 가청 한계"),
        Params(listOf(-4.0, -2.0, 0.0, 2.0, 4.0)) to LevelSpec(4, "2 dB 간격", "미묘함"),
        Params(listOf(-3.0, -1.5, 0.0, 1.5, 3.0)) to LevelSpec(5, "1.5 dB 간격", "아주 미묘함"),
        Params(listOf(-2.0, -1.0, 0.0, 1.0, 2.0)) to LevelSpec(6, "1 dB 간격", "전문가 영역"),
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
                t > 0 -> "밝게 +$t dB"
                t < 0 -> "어둡게 ${-t} dB"
                else -> "평탄"
            }
            Choice(id = "t$i", label = label, ordinal = t)
        }

        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "톤 밸런스는 어느 쪽으로 기울었나?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "t$index",
            random = random,
        )
    }
}
