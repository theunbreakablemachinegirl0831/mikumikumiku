package mmm.app.playback

import mmm.audio.AudioOutput
import mmm.audio.PlaybackEngine
import mmm.source.file.SwitchableClipSource
import mmm.training.render.RenderedQuestion

/**
 * Plays a rendered question's stimuli, switching between them without losing the place.
 *
 * Switching in place is the whole point: comparing A and B by restarting each from the top turns
 * the task into remembering how the intro sounded, whereas flipping between them on the same bar is
 * how the comparison is actually made. Every stimulus of a question is rendered from the same
 * excerpt, so they share a length and a position means the same thing in each.
 */
class StimulusPlayer {

    private var output: AudioOutput? = null
    private var source: SwitchableClipSource? = null
    private var engine: PlaybackEngine? = null
    private var sampleRate = 0
    private var rendered: RenderedQuestion? = null
    private var running = false

    /** The stimulus currently audible, or null when stopped. */
    var playingId: String? = null
        private set

    /** Loads a new question. Playback stops and the next play starts from the beginning. */
    fun load(question: RenderedQuestion) {
        stop()
        applyCommonHeadroom(question)
        rendered = question

        val first = question.stimuli.firstOrNull() ?: return
        if (engine == null || sampleRate != question.sampleRate) {
            release()
            val out = AudioOutput(question.sampleRate, first.audio.channels)
            val clip = SwitchableClipSource(first.audio, question.sampleRate, loop = true)
            output = out
            source = clip
            engine = PlaybackEngine(clip, out)
            sampleRate = question.sampleRate
        } else {
            source?.select(first.audio)
        }
        source?.rewind()
    }

    /** Starts or switches to [stimulusId], keeping the playhead where it is. */
    fun play(stimulusId: String) {
        val stimulus = rendered?.get(stimulusId) ?: return
        val clip = source ?: return
        clip.select(stimulus.audio)
        if (!running) {
            engine?.start()
            running = true
        }
        playingId = stimulusId
    }

    fun stop() {
        if (running) {
            engine?.stop()
            running = false
        }
        source?.rewind()
        playingId = null
    }

    fun release() {
        stop()
        engine?.release()
        engine = null
        output = null
        source = null
        sampleRate = 0
    }

    /**
     * Scales every stimulus by one shared factor if any of them would clip.
     *
     * Shared, not per stimulus: the loudness match has already put them all at the same level, and
     * scaling each to its own peak would undo exactly that.
     */
    private fun applyCommonHeadroom(question: RenderedQuestion) {
        val peak = question.stimuli.maxOfOrNull { it.audio.peak() } ?: return
        if (peak <= CEILING) return
        val gain = CEILING / peak
        for (stimulus in question.stimuli) {
            val audio = stimulus.audio
            for (ch in 0 until audio.channels) {
                val samples = audio.data[ch]
                for (i in 0 until audio.frames) samples[i] *= gain
            }
        }
    }

    private companion object {
        const val CEILING = 0.98f
    }
}
