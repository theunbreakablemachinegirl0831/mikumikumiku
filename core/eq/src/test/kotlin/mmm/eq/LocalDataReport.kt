package mmm.eq

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * Fits real measurements from `local-data/squig/` when they are there, and prints what came out.
 *
 * The measurements belong to the people who published them, so they are not in the repository
 * (`local-data/` is ignored) and this skips itself everywhere else. Run it with
 * `./gradlew :core:eq:test --tests '*LocalDataReport*' -i`.
 */
class LocalDataReport {

    private val folder = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "local-data/squig") }
        .firstOrNull { it.isDirectory }

    private fun read(name: String): FrequencyResponse? =
        folder?.let { File(it, "$name.txt") }?.takeIf { it.exists() }?.let { FrequencyResponse.parse(it.readText()) }

    @Test
    fun `fit See Audio Bravo`() {
        val left = read("SeeAudio Bravo L")
        val right = read("SeeAudio Bravo R")
        assumeTrue(left != null && right != null)
        val measured = FrequencyResponse.average(listOf(left!!, right!!))

        for (targetName in listOf("Harman IE 2019 for 5128 Target", "JM-1 (5128) Target")) {
            val target = read(targetName) ?: continue
            for ((label, settings) in listOf("10 kHz limit" to PeqFitSettings.DEFAULT, "full range" to PeqFitSettings.FULL_RANGE)) {
                val result = PeqFitter(settings).fit(measured, target)
                println("=== Bravo -> $targetName ($label)")
                println(
                    String.format(
                        Locale.ROOT,
                        "RMS error %.2f dB -> %.2f dB",
                        result.rmsBeforeDb,
                        result.rmsAfterDb,
                    )
                )
                print(result.profile.toApoText())
                val checkpoints = listOf(30, 60, 100, 200, 500, 1000, 1300, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 12000, 16000)
                println("Hz      wanted   eq     left")
                for (hz in checkpoints) {
                    val f = hz.toDouble()
                    val eq = result.profile.magnitudeDbAt(f) - result.profile.preampDb
                    println(String.format(Locale.ROOT, "%6d  %6.1f  %6.1f  %6.1f", hz, result.wanted.at(f), eq, result.wanted.at(f) - eq))
                }
            }
        }
    }
}
