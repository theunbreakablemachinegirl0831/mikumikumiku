package mmm.usb

/**
 * Interleaved float samples passed from one producer thread to one consumer thread without locks.
 *
 * Sits between the processing thread and the USB feeder. Neither side ever blocks inside it; the
 * sink decides what to do when it is full and the feeder decides what to do when it is short, so
 * the audio thread's timing never depends on the other thread holding a lock.
 */
public class SpscFloatRing(public val capacityFrames: Int, public val channels: Int) {
    init {
        require(capacityFrames > 0) { "capacity must be positive" }
        require(channels > 0) { "channels must be positive" }
    }

    private val storage = FloatArray(capacityFrames * channels)

    // Frame counters, only ever increased; each is written by one thread and read by the other.
    @Volatile private var written = 0L
    @Volatile private var read = 0L

    public val availableFrames: Int get() = (written - read).toInt()

    public val freeFrames: Int get() = capacityFrames - availableFrames

    /**
     * Copies up to [frames] frames from [source] starting at frame [offsetFrames].
     * @return frames actually written
     */
    public fun write(source: FloatArray, frames: Int, offsetFrames: Int = 0): Int {
        val count = minOf(frames, freeFrames)
        if (count <= 0) return 0
        copy(source, offsetFrames * channels, (written % capacityFrames).toInt(), count, toRing = true)
        written += count
        return count
    }

    /**
     * Copies up to [frames] frames into [target] starting at its beginning.
     * @return frames actually read
     */
    public fun read(target: FloatArray, frames: Int): Int {
        val count = minOf(frames, availableFrames)
        if (count <= 0) return 0
        copy(target, 0, (read % capacityFrames).toInt(), count, toRing = false)
        read += count
        return count
    }

    /** Drops everything buffered. Only safe while the consumer is not reading. */
    public fun clear() {
        read = written
    }

    private fun copy(array: FloatArray, arrayOffset: Int, ringFrame: Int, frames: Int, toRing: Boolean) {
        val firstFrames = minOf(frames, capacityFrames - ringFrame)
        val firstSamples = firstFrames * channels
        val restSamples = (frames - firstFrames) * channels
        val ringOffset = ringFrame * channels
        if (toRing) {
            System.arraycopy(array, arrayOffset, storage, ringOffset, firstSamples)
            System.arraycopy(array, arrayOffset + firstSamples, storage, 0, restSamples)
        } else {
            System.arraycopy(storage, ringOffset, array, arrayOffset, firstSamples)
            System.arraycopy(storage, 0, array, arrayOffset + firstSamples, restSamples)
        }
    }
}
