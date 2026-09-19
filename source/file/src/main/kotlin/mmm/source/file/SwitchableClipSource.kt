package mmm.source.file

import mmm.dsp.stream.AudioSource
import mmm.dsp.AudioBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays whichever rendered stimulus is currently selected, **keeping the playback position** when
 * the selection changes.
 *
 * Position-preserving switching is the point. Comparing A and B by restarting each from the top
 * turns the task into remembering how the intro sounded; switching in place on the same bar is how
 * the comparison is actually made, and is what "How to Listen" style A/B work depends on.
 */
public class SwitchableClipSource(
    initial: AudioBuffer,
    override val sampleRate: Int,
    public val loop: Boolean = true,
) : AudioSource {

    private val current = AtomicReference(initial)

    override val channels: Int get() = current.get().channels

    @Volatile
    public var position: Int = 0
        private set

    /** Swaps the audio without moving the playhead. */
    public fun select(audio: AudioBuffer) {
        require(audio.channels == channels) { "stimulus channel count changed mid-playback" }
        current.set(audio)
    }

    public fun rewind() {
        position = 0
    }

    public fun seekSeconds(seconds: Double) {
        position = (seconds * sampleRate).toInt().coerceAtLeast(0)
    }

    public val positionSeconds: Double get() = position.toDouble() / sampleRate

    override fun read(buffer: AudioBuffer): Int {
        val clip = current.get()
        var start = position
        if (start >= clip.frames) {
            if (!loop) return -1
            start = 0
        }
        val count = minOf(buffer.capacity, clip.frames - start)
        buffer.frames = count
        for (ch in 0 until buffer.channels) {
            clip.data[ch].copyInto(buffer.data[ch], 0, start, start + count)
        }
        position = start + count
        return count
    }
}
