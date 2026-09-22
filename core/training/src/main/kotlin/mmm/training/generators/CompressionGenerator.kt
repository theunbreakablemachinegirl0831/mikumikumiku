package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.FilterOverrides
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import mmm.training.QuestionFormat
import mmm.training.Stimulus
import kotlin.random.Random

/**
 * Dynamics training.
 *
 * Detection first, then ranking. Ranking is the interesting half: "is this compressed" is nearly
 * meaningless on commercial music, which is already compressed, whereas "which of these three is
 * compressed hardest" is a judgement that transfers directly to mix and mastering decisions - and
 * it works on the live-capture mode, where the source material is whatever is playing.
 */
public class CompressionGenerator : ExerciseGenerator {

    private data class Params(
        val ratios: List<Double>,
        val thresholdDb: Double,
        val attackMs: Double,
        val releaseMs: Double,
        val rank: Boolean,
    )

    private val ladder = listOf(
        Params(listOf(1.0, 20.0), -35.0, 1.0, 60.0, false) to
            LevelSpec(0, "눌렸나 안 눌렸나", "리미팅 vs 원본"),
        Params(listOf(1.0, 8.0), -30.0, 3.0, 80.0, false) to
            LevelSpec(1, "강한 컴프레션", "8:1 vs 원본"),
        Params(listOf(1.0, 4.0), -25.0, 10.0, 120.0, false) to
            LevelSpec(2, "보통", "4:1, 느린 어택"),
        Params(listOf(1.0, 2.0), -22.0, 20.0, 200.0, false) to
            LevelSpec(3, "완만함", "2:1 - 대부분이 못 듣기 시작하는 지점"),
        Params(listOf(1.5, 4.0, 12.0), -28.0, 5.0, 100.0, true) to
            LevelSpec(4, "3개 순위", "세 가지 컴프레션 양을 순서대로"),
        Params(listOf(2.0, 4.0, 8.0), -25.0, 10.0, 150.0, true) to
            LevelSpec(5, "순위 · 더 가깝게", "비율이 한 단계씩만 차이난다"),
        Params(listOf(2.0, 3.0, 4.0, 6.0), -25.0, 15.0, 180.0, true) to
            LevelSpec(6, "4개 순위", "자극 4개, 작은 비율 차이"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.COMPRESSION

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random, overrides: FilterOverrides): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        return if (params.rank) generateRanking(level, params, random) else generateDetection(level, params, random)
    }

    private fun generateDetection(level: Int, params: Params, random: Random): Question {
        val ratio = params.ratios.last()
        val spec = ArtifactSpec.Compression(
            thresholdDb = params.thresholdDb,
            ratio = ratio,
            attackMs = params.attackMs,
            releaseMs = params.releaseMs,
        )
        return GeneratorSupport.forcedChoice(
            family = family,
            level = level,
            prompt = "컴프레션(Compression)이 걸린 것은?",
            artifact = spec,
            alternatives = 2 + (level / 3),
            random = random,
        )
    }

    private fun generateRanking(level: Int, params: Params, random: Random): Question {
        val specs = params.ratios.map { ratio ->
            ratio to ArtifactSpec.Compression(
                thresholdDb = params.thresholdDb,
                ratio = ratio,
                attackMs = params.attackMs,
                releaseMs = params.releaseMs,
            )
        }
        // Present in random order; the answer is the order by ratio, least squashed first.
        val presented = specs.shuffled(random)
        val stimuli = presented.mapIndexed { i, (_, spec) ->
            Stimulus(id = GeneratorSupport.letter(i), label = GeneratorSupport.letter(i), spec = spec)
        }
        val correctOrder = presented
            .mapIndexed { i, (ratio, _) -> GeneratorSupport.letter(i) to ratio }
            .sortedBy { it.second }
            .map { it.first }

        return Question(
            id = GeneratorSupport.questionId(random),
            family = family,
            format = QuestionFormat.RANK,
            prompt = "덜 눌린 것부터 순서대로 누른다.",
            stimuli = stimuli,
            choices = stimuli.map { Choice(it.id, it.label) },
            correctChoiceIds = correctOrder,
            level = level,
            explanation = "각 자극의 비율: " + presented
                .mapIndexed { i, (ratio, _) -> "${GeneratorSupport.letter(i)} = ${ratio}:1" }
                .joinToString(", "),
        )
    }
}
