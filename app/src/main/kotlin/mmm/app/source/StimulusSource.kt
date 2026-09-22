package mmm.app.source

import android.content.Context
import android.net.Uri
import mmm.app.data.SourceChoice
import mmm.dsp.AudioBuffer
import mmm.dsp.SignalGenerator
import mmm.source.file.ClipDecoder
import kotlin.random.Random

/** Audio ready to have exercises rendered onto it. */
class LoadedSource(
    val audio: AudioBuffer,
    val sampleRate: Int,
    val label: String,
)

/**
 * Turns the learner's source choice into an excerpt in memory.
 *
 * Every excerpt is peak-normalised to the same level before any artifact is applied. That leaves a
 * fixed amount of headroom for the processing - a +12 dB band boost on a track already at 0 dBFS
 * would otherwise clip, and clipping is an artifact of its own that the question is not about.
 */
object StimulusSource {

    private const val PINK_SAMPLE_RATE = 48000

    /** About -10 dBFS: room for a +12 dB boost before the loudness match pulls it back. */
    private const val TARGET_PEAK = 0.3f

    /** Songs rarely do anything useful in their first seconds, so file excerpts skip in a little. */
    private const val FILE_START_SECONDS = 30.0

    fun load(context: Context, choice: SourceChoice, seconds: Int): LoadedSource = when (choice) {
        SourceChoice.PinkNoise -> pinkNoise(seconds)
        is SourceChoice.File -> file(context, choice, seconds)
    }

    private fun pinkNoise(seconds: Int): LoadedSource {
        val audio = SignalGenerator.pinkNoise(
            frames = PINK_SAMPLE_RATE * seconds,
            channels = 2,
            random = Random(System.nanoTime()),
        )
        normalise(audio)
        return LoadedSource(audio, PINK_SAMPLE_RATE, SourceChoice.PinkNoise.label)
    }

    private fun file(context: Context, choice: SourceChoice.File, seconds: Int): LoadedSource {
        val uri = Uri.parse(choice.uri)
        // A track shorter than the skip-in point decodes to nothing; start from the top instead
        // of failing on what is really just a short file.
        val clip = runCatching {
            ClipDecoder.decode(context, uri, FILE_START_SECONDS, seconds.toDouble())
        }.recoverCatching {
            ClipDecoder.decode(context, uri, 0.0, seconds.toDouble())
        }.getOrThrow()

        normalise(clip.audio)
        return LoadedSource(clip.audio, clip.sampleRate, choice.name)
    }

    private fun normalise(audio: AudioBuffer) {
        val peak = audio.peak()
        if (peak <= 1e-6f) return
        val gain = TARGET_PEAK / peak
        for (ch in 0 until audio.channels) {
            val samples = audio.data[ch]
            for (i in 0 until audio.frames) samples[i] *= gain
        }
    }
}
