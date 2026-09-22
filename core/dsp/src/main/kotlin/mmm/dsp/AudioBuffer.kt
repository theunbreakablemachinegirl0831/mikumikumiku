package mmm.dsp

/**
 * Planar (de-interleaved) float audio, `[channel][frame]`, samples nominally in `-1f..1f`.
 *
 * Planar layout keeps the per-channel filter loops tight, which matters because the whole chain
 * runs on the capture callback thread. [frames] is mutable so a block can be partially filled
 * without reallocating; [capacity] is the allocated size.
 */
public class AudioBuffer(public val channels: Int, public val capacity: Int) {

    public val data: Array<FloatArray> = Array(channels) { FloatArray(capacity) }

    public var frames: Int = capacity
        set(value) {
            require(value in 0..capacity) { "frames $value out of range 0..$capacity" }
            field = value
        }

    public operator fun get(channel: Int): FloatArray = data[channel]

    public fun clear() {
        for (ch in data) ch.fill(0f)
    }

    /** Copies `frames * channels` interleaved samples from [src] into this buffer. */
    public fun readInterleaved(src: FloatArray, frameCount: Int, srcOffset: Int = 0) {
        require(frameCount <= capacity) { "frameCount $frameCount exceeds capacity $capacity" }
        frames = frameCount
        for (ch in 0 until channels) {
            val dst = data[ch]
            var i = srcOffset + ch
            for (f in 0 until frameCount) {
                dst[f] = src[i]
                i += channels
            }
        }
    }

    /** Writes this buffer back out as interleaved samples into [dst]. */
    public fun writeInterleaved(dst: FloatArray, dstOffset: Int = 0) {
        for (ch in 0 until channels) {
            val srcCh = data[ch]
            var i = dstOffset + ch
            for (f in 0 until frames) {
                dst[i] = srcCh[f]
                i += channels
            }
        }
    }

    /** Converts 16-bit PCM (the format `AudioRecord` hands us) into planar floats. */
    public fun readInterleavedPcm16(src: ShortArray, frameCount: Int, srcOffset: Int = 0) {
        require(frameCount <= capacity) { "frameCount $frameCount exceeds capacity $capacity" }
        frames = frameCount
        for (ch in 0 until channels) {
            val dst = data[ch]
            var i = srcOffset + ch
            for (f in 0 until frameCount) {
                dst[f] = src[i] / 32768f
                i += channels
            }
        }
    }

    public fun writeInterleavedPcm16(dst: ShortArray, dstOffset: Int = 0) {
        for (ch in 0 until channels) {
            val srcCh = data[ch]
            var i = dstOffset + ch
            for (f in 0 until frames) {
                val v = (srcCh[f] * 32767f)
                dst[i] = when {
                    v > 32767f -> Short.MAX_VALUE
                    v < -32768f -> Short.MIN_VALUE
                    else -> v.toInt().toShort()
                }
                i += channels
            }
        }
    }

    public fun copyFrom(other: AudioBuffer) {
        require(other.channels == channels) { "channel count mismatch" }
        frames = other.frames
        for (ch in 0 until channels) {
            other.data[ch].copyInto(data[ch], 0, 0, other.frames)
        }
    }

    public fun copy(): AudioBuffer = AudioBuffer(channels, capacity).also { it.copyFrom(this) }

    /** Root-mean-square across every channel of the currently filled region. */
    public fun rms(): Double {
        if (frames == 0) return 0.0
        var sum = 0.0
        for (ch in 0 until channels) {
            val c = data[ch]
            for (f in 0 until frames) {
                val v = c[f].toDouble()
                sum += v * v
            }
        }
        return kotlin.math.sqrt(sum / (frames * channels))
    }

    public fun peak(): Float {
        var p = 0f
        for (ch in 0 until channels) {
            val c = data[ch]
            for (f in 0 until frames) {
                val a = kotlin.math.abs(c[f])
                if (a > p) p = a
            }
        }
        return p
    }
}
