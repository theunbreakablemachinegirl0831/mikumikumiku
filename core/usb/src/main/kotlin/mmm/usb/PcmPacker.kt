package mmm.usb

import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * Turns interleaved float samples into the bytes a USB audio endpoint expects.
 *
 * USB audio is little-endian and left-justified: a 24-bit sample in a 4-byte subslot occupies the
 * top three bytes. Quantising to 16 bits gets TPDF dither, because truncation distortion on a
 * quiet fade is exactly the kind of low-level artefact a listening trainer should not add on its
 * own; at 24 bits and above the dither is far below anything audible and is skipped.
 */
public class PcmPacker(
    public val format: UsbPcmFormat,
    public val dither: Boolean = format.bitResolution <= 16,
    seed: Long = 0x2545F4914F6CDD1DL,
) {
    private val bits = format.bitResolution.coerceIn(8, 32)
    private val bytes = format.bytesPerSample
    private val shift = bytes * 8 - bits
    private val scale = (1L shl (bits - 1)).toDouble()
    private val maxCode = (1L shl (bits - 1)) - 1
    private val minCode = -(1L shl (bits - 1))

    init {
        require(bytes in 1..4) { "unsupported subslot size $bytes" }
        require(bits <= bytes * 8) { "$bits bits do not fit in $bytes bytes" }
    }

    private var state = if (seed == 0L) 1L else seed

    /**
     * Packs [frames] frames of [interleaved] into [out] starting at byte [offset].
     * @return bytes written
     */
    public fun pack(interleaved: FloatArray, frames: Int, out: ByteBuffer, offset: Int): Int {
        val samples = frames * format.channels
        var position = offset
        for (i in 0 until samples) {
            val code = quantise(interleaved[i]) shl shift
            var remaining = bytes
            var value = code
            while (remaining > 0) {
                out.put(position++, (value and 0xFF).toByte())
                value = value shr 8
                remaining--
            }
        }
        return position - offset
    }

    /** Writes [frames] frames of digital silence. */
    public fun silence(frames: Int, out: ByteBuffer, offset: Int): Int {
        val length = frames * format.bytesPerFrame()
        for (i in 0 until length) out.put(offset + i, 0)
        return length
    }

    private fun quantise(sample: Float): Long {
        var scaled = sample.toDouble() * scale
        if (dither) scaled += uniform() + uniform()
        val code = floor(scaled + 0.5).toLong()
        return code.coerceIn(minCode, maxCode)
    }

    /** Uniform in [-0.5, 0.5), from xorshift64 - no allocation on the audio thread. */
    private fun uniform(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return (x ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5
    }
}
