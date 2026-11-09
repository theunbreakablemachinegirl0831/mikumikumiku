package mmm.app.source

import android.content.Context
import android.net.Uri
import mmm.app.data.SourceChoice
import mmm.dsp.AudioBuffer
import mmm.dsp.SignalGenerator
import mmm.dsp.SpectrumProfile
import mmm.source.file.ClipDecoder
import kotlin.random.Random

/** Audio ready to have exercises rendered onto it. */
class LoadedSource(
    val audio: AudioBuffer,
    val sampleRate: Int,
    val label: String,
    /** What the excerpt has energy in, so questions are only aimed where there is something to hear. */
    val spectrum: SpectrumProfile? = null,
    /** Where in the file the excerpt starts, for the header. */
    val startSeconds: Double? = null,
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

    /** Songs rarely do anything useful in their first seconds, so the search skips in a little. */
    private const val SEARCH_START_SECONDS = 15.0

    /** How much of the song is searched for the excerpt with the fullest spectrum. */
    private const val SEARCH_SECONDS = 60.0

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

    /**
     * A minute of the song is decoded and searched for the [seconds]-long stretch whose spectrum
     * is most complete. A fixed cut point used to land on intros with no bass or breakdowns with
     * no treble often enough that Band ID questions asked about bands the excerpt did not contain.
     */
    private fun file(context: Context, choice: SourceChoice.File, seconds: Int): LoadedSource {
        val uri = Uri.parse(choice.uri)
        // A track shorter than the skip-in point decodes to nothing; start from the top instead
        // of failing on what is really just a short file.
        var searchStart = SEARCH_START_SECONDS
        val window = runCatching {
            ClipDecoder.decode(context, uri, SEARCH_START_SECONDS, SEARCH_SECONDS)
        }.recoverCatching {
            searchStart = 0.0
            ClipDecoder.decode(context, uri, 0.0, SEARCH_SECONDS)
        }.getOrThrow()

        val rate = window.sampleRate
        val length = (seconds * rate).coerceAtMost(window.audio.frames)
        val start = SpectrumProfile.bestExcerptStart(window.audio, rate, length)
        val audio = AudioBuffer(window.audio.channels, length).apply {
            frames = length
            for (ch in 0 until channels) window.audio.data[ch].copyInto(data[ch], 0, start, start + length)
        }

        normalise(audio)
        return LoadedSource(
            audio = audio,
            sampleRate = rate,
            label = choice.name,
            spectrum = SpectrumProfile.analyze(audio, rate),
            startSeconds = searchStart + start.toDouble() / rate,
        )
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
