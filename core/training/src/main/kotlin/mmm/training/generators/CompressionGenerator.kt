package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
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
            LevelSpec(0, "Squashed or not", "Limiting against untouched audio"),
        Params(listOf(1.0, 8.0), -30.0, 3.0, 80.0, false) to
            LevelSpec(1, "Heavy compression", "8:1 against untouched audio"),
        Params(listOf(1.0, 4.0), -25.0, 10.0, 120.0, false) to
            LevelSpec(2, "Moderate", "4:1, slower attack"),
        Params(listOf(1.0, 2.0), -22.0, 20.0, 200.0, false) to
            LevelSpec(3, "Gentle", "2:1 - the point where most people stop hearing it"),
        Params(listOf(1.5, 4.0, 12.0), -28.0, 5.0, 100.0, true) to
            LevelSpec(4, "Rank three", "Put three amounts of compression in order"),
        Params(listOf(2.0, 4.0, 8.0), -25.0, 10.0, 150.0, true) to
            LevelSpec(5, "Rank, closer", "Ratios only one step apart"),
        Params(listOf(2.0, 3.0, 4.0, 6.0), -25.0, 15.0, 180.0, true) to
            LevelSpec(6, "Rank four", "Four stimuli, small ratio steps"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.COMPRESSION

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
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
            prompt = "Which one is compressed?",
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
            prompt = "Put these in order, least compressed first.",
            stimuli = stimuli,
            choices = stimuli.map { Choice(it.id, it.label) },
            correctChoiceIds = correctOrder,
            level = level,
            explanation = presented
                .mapIndexed { i, (ratio, _) -> "${GeneratorSupport.letter(i)} = ${ratio}:1" }
                .joinToString(", "),
        )
    }
}
