package mmm.training

import mmm.training.generators.BandIdGenerator
import mmm.training.generators.BandwidthGenerator
import mmm.training.generators.CompressionGenerator
import mmm.training.generators.DistortionGenerator
import mmm.training.generators.ResonanceGenerator
import mmm.training.generators.ReverbGenerator
import mmm.training.generators.SpectralBalanceGenerator

/** Every exercise the trainer knows how to produce. */
public object Curriculum {

    private val generators: Map<ExerciseFamily, ExerciseGenerator> = listOf(
        BandIdGenerator(),
        ResonanceGenerator(),
        BandwidthGenerator(),
        DistortionGenerator(),
        CompressionGenerator(),
        ReverbGenerator(),
        SpectralBalanceGenerator(),
    ).associateBy { it.family }

    public val families: List<ExerciseFamily> get() = ExerciseFamily.entries.toList()

    public fun generator(family: ExerciseFamily): ExerciseGenerator =
        generators.getValue(family)

    public fun levels(family: ExerciseFamily): List<LevelSpec> = generator(family).levels

    public fun maxLevel(family: ExerciseFamily): Int = levels(family).lastIndex
}
