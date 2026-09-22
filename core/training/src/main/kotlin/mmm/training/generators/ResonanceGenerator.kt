package mmm.training.generators

import mmm.training.ArtifactSpec
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.FilterOverrides
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import kotlin.math.pow
import kotlin.random.Random

/**
 * "Which of these is ringing?"
 *
 * Forced choice rather than identification, because a resonance near threshold is something you
 * *notice* before you can place it - and pushing that noticing threshold down is the point. Rising
 * Q makes it harder, not easier: a narrower peak excites less of the programme material even
 * though its gain is unchanged.
 */
public class ResonanceGenerator : ExerciseGenerator {

    private data class Params(val q: Double, val gainDb: Double, val alternatives: Int)

    private val ladder = listOf(
        Params(6.0, 12.0, 2) to LevelSpec(0, "뚜렷한 울림", "Q 6, +12 dB, 보기 2개"),
        Params(8.0, 9.0, 2) to LevelSpec(1, "Q 8 · 9 dB", "조금 더 좁고 작다"),
        Params(10.0, 7.0, 3) to LevelSpec(2, "보기 3개", "Q 10, +7 dB"),
        Params(14.0, 5.0, 3) to LevelSpec(3, "Q 14 · 5 dB", "좁아지기 시작한다"),
        Params(20.0, 4.0, 3) to LevelSpec(4, "Q 20 · 4 dB", "믹스 속에 숨을 만큼 좁다"),
        Params(28.0, 3.0, 3) to LevelSpec(5, "Q 28 · 3 dB", "대부분의 청취자에게 역치 근처"),
        Params(40.0, 2.0, 3) to LevelSpec(6, "Q 40 · 2 dB", "훈련된 청취자의 영역"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.RESONANCE

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random, overrides: FilterOverrides): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        // Log-uniform across 100 Hz - 8 kHz; uniform in Hz would put nearly every trial in the treble.
        val frequency = 100.0 * (80.0).pow(random.nextDouble())
        val spec = ArtifactSpec.Resonance(
            frequencyHz = frequency,
            q = overrides.q ?: params.q,
            gainDb = overrides.gainDb ?: params.gainDb,
        )

        return GeneratorSupport.forcedChoice(
            family = family,
            level = level,
            prompt = "레조넌스(Resonance)가 있는 것은?",
            artifact = spec,
            alternatives = params.alternatives,
            random = random,
        )
    }
}
