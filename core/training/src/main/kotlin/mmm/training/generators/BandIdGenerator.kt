package mmm.training.generators

import mmm.dsp.BandGrid
import mmm.dsp.BandResolution
import mmm.training.ArtifactSpec
import mmm.training.Choice
import mmm.training.ExerciseFamily
import mmm.training.ExerciseGenerator
import mmm.training.GeneratorSupport
import mmm.training.LevelSpec
import mmm.training.Question
import kotlin.random.Random

/**
 * "Which band was changed?" - the backbone exercise.
 *
 * The ladder tightens three things in turn: the grid gets finer (so the answers sit closer
 * together), the gain gets smaller (so the change gets subtler), and cuts join boosts (a dip is
 * consistently harder to place than a peak).
 */
public class BandIdGenerator : ExerciseGenerator {

    private data class Params(
        val resolution: BandResolution,
        val lowHz: Double,
        val highHz: Double,
        val gainDb: Double,
        val allowCuts: Boolean,
    )

    private val ladder = listOf(
        Params(BandResolution.OCTAVE, 125.0, 4000.0, 12.0, false) to
            LevelSpec(0, "옥타브 · 중역만", "옥타브 밴드 5개, +12 dB 큰 부스트"),
        Params(BandResolution.OCTAVE, 63.0, 16000.0, 12.0, false) to
            LevelSpec(1, "옥타브 · 전 대역", "전체 스펙트럼, 여전히 +12 dB"),
        Params(BandResolution.OCTAVE, 31.5, 16000.0, 9.0, true) to
            LevelSpec(2, "옥타브 · 부스트와 컷", "이제 딥(컷)도 나온다, 9 dB"),
        Params(BandResolution.HALF_OCTAVE, 63.0, 16000.0, 9.0, false) to
            LevelSpec(3, "1/2 옥타브", "고를 답이 두 배로 늘어난다"),
        Params(BandResolution.HALF_OCTAVE, 63.0, 16000.0, 6.0, true) to
            LevelSpec(4, "1/2 옥타브 · 6 dB", "1/2 옥타브 격자, 6 dB 부스트와 컷"),
        Params(BandResolution.THIRD_OCTAVE, 63.0, 16000.0, 6.0, false) to
            LevelSpec(5, "1/3 옥타브", "대부분의 측정 작업에서 쓰는 해상도"),
        Params(BandResolution.THIRD_OCTAVE, 40.0, 16000.0, 4.0, true) to
            LevelSpec(6, "1/3 옥타브 · 4 dB", "1/3 옥타브 격자, 4 dB"),
        Params(BandResolution.SIXTH_OCTAVE, 63.0, 16000.0, 4.0, false) to
            LevelSpec(7, "1/6 옥타브", "아주 촘촘한 격자, 4 dB"),
        Params(BandResolution.SIXTH_OCTAVE, 40.0, 16000.0, 2.5, true) to
            LevelSpec(8, "1/6 옥타브 · 2.5 dB", "훈련된 청취자의 한계에 가까운 수준"),
    )

    override val family: ExerciseFamily get() = ExerciseFamily.BAND_ID

    override val levels: List<LevelSpec> = ladder.map { it.second }

    override fun generate(level: Int, random: Random): Question {
        val params = ladder[level.coerceIn(ladder.indices)].first
        val grid = BandGrid(params.resolution, params.lowHz, params.highHz)
        val band = grid.bands[random.nextInt(grid.size)]

        val gainDb = if (params.allowCuts && random.nextBoolean()) -params.gainDb else params.gainDb
        val spec = ArtifactSpec.BandBoost(band, gainDb)

        val choices = grid.bands.map { Choice(id = "b${it.index}", label = it.label, ordinal = it.centerHz) }
        val direction = if (gainDb >= 0) "부스트" else "컷"

        return GeneratorSupport.identify(
            family = family,
            level = level,
            prompt = "어느 밴드가 ${direction}되었나?",
            artifact = spec,
            choices = choices,
            correctChoiceId = "b${band.index}",
            random = random,
        )
    }
}
