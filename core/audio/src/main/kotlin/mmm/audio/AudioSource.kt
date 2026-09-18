package mmm.audio

import mmm.dsp.AudioBuffer

/**
 * A pull-model supplier of PCM.
 *
 * Both playback modes are behind this one interface: a decoded clip reads out of memory, the live
 * capture reads out of a ring buffer being filled by `AudioRecord`. [PlaybackEngine] does not know
 * which it has, which is the whole reason a training session behaves the same in either mode.
 */
public interface AudioSource {

    public val sampleRate: Int
    public val channels: Int

    /**
     * Fills [buffer] with up to `buffer.capacity` frames and sets `buffer.frames` accordingly.
     *
     * @return frames written; 0 means "nothing available right now" (live capture between
     *   callbacks), -1 means the source is exhausted and playback should stop.
     */
    public fun read(buffer: AudioBuffer): Int

    public fun close() {}
}

/** An in-memory decoded excerpt: the file mode's source. */
public class ClipSource(
    private val clip: AudioBuffer,
    override val sampleRate: Int,
    public val loop: Boolean = true,
) : AudioSource {

    override val channels: Int get() = clip.channels

    private var position = 0

    public val lengthFrames: Int get() = clip.frames

    public fun seek(frame: Int) {
        position = frame.coerceIn(0, clip.frames)
    }

    public fun rewind(): Unit = seek(0)

    override fun read(buffer: AudioBuffer): Int {
        if (position >= clip.frames) {
            if (!loop) return -1
            position = 0
        }
        val count = minOf(buffer.capacity, clip.frames - position)
        buffer.frames = count
        for (ch in 0 until channels) {
            clip.data[ch].copyInto(buffer.data[ch], 0, position, position + count)
        }
        position += count
        return count
    }
}

/**
 * Lock-free-enough single-producer/single-consumer ring buffer bridging the capture callback
 * thread to the playback thread.
 *
 * Under-runs are reported rather than hidden: on the live path a silent gap is indistinguishable
 * from an app that is blocking capture, and the difference is exactly what the setup screen has to
 * tell the user.
 */
public class RingBufferSource(
    override val sampleRate: Int,
    override val channels: Int,
    capacityFrames: Int,
) : AudioSource {

    private val capacity = capacityFrames
    private val storage = Array(channels) { FloatArray(capacity) }

    @Volatile private var writeIndex = 0L
    @Volatile private var readIndex = 0L

    @Volatile
    public var underrunFrames: Long = 0L
        private set

    @Volatile
    public var overrunFrames: Long = 0L
        private set

    public val availableFrames: Int get() = (writeIndex - readIndex).toInt().coerceAtLeast(0)

    /** Called from the capture thread. Oldest audio is dropped if the reader has fallen behind. */
    public fun write(source: AudioBuffer) {
        val count = source.frames
        if (count <= 0) return

        val free = capacity - availableFrames
        if (count > free) {
            val drop = count - free
            readIndex += drop
            overrunFrames += drop
        }

        var w = (writeIndex % capacity).toInt()
        var remaining = count
        var offset = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - w)
            for (ch in 0 until channels) {
                source.data[ch].copyInto(storage[ch], w, offset, offset + chunk)
            }
            w = (w + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        writeIndex += count
    }

    override fun read(buffer: AudioBuffer): Int {
        val wanted = buffer.capacity
        val have = availableFrames
        if (have == 0) {
            buffer.frames = 0
            return 0
        }
        val count = minOf(wanted, have)
        if (count < wanted) underrunFrames += (wanted - count)

        var r = (readIndex % capacity).toInt()
        var remaining = count
        var offset = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - r)
            for (ch in 0 until channels) {
                storage[ch].copyInto(buffer.data[ch], offset, r, r + chunk)
            }
            r = (r + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        readIndex += count
        buffer.frames = count
        return count
    }

    public fun clear() {
        readIndex = writeIndex
    }

    public fun resetCounters() {
        underrunFrames = 0
        overrunFrames = 0
    }
}
