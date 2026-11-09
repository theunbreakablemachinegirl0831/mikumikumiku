@file:OptIn(UnstableApi::class)

package mmm.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import mmm.dsp.AudioBuffer
import mmm.eq.EqProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The earphone EQ inside ExoPlayer's audio pipeline.
 *
 * Works on 16-bit PCM because that is what the pipeline hands its processors once float output is
 * off; the samples are filtered in float and dithered back to 16 bits, so the EQ's preamp - which
 * leaves the loudest boost just under full scale - is what keeps it from clipping.
 *
 * The profile is swapped from the UI thread and picked up on the audio thread at the next buffer;
 * building a new filter chain allocates, which is acceptable at the moment the EQ changes.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var requested: EqProfile? = null
    @Volatile private var dirty = true

    private var processor: mmm.dsp.AudioProcessor? = null
    private var block = AudioBuffer(2, BLOCK_FRAMES)
    private val random = Random(0x5EED)

    fun setProfile(profile: EqProfile?) {
        requested = profile
        dirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        dirty = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount
        val bytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(bytes)
        if (dirty) rebuild(format)

        val active = processor
        if (active == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        var frames = bytes / (2 * channels)
        while (frames > 0) {
            val count = minOf(frames, BLOCK_FRAMES)
            block.frames = count
            for (i in 0 until count) {
                for (ch in 0 until channels) block.data[ch][i] = input.short / 32768f
            }
            active.process(block)
            for (i in 0 until count) {
                for (ch in 0 until channels) {
                    // TPDF dither: two uniform values summed, one LSB peak to peak each.
                    val dither = random.nextFloat() - random.nextFloat()
                    val value = (block.data[ch][i] * 32768f + dither).toInt().coerceIn(-32768, 32767)
                    output.putShort(value.toShort())
                }
            }
            frames -= count
        }
        output.flip()
    }

    override fun onFlush() {
        processor?.reset()
    }

    override fun onReset() {
        processor = null
        dirty = true
    }

    private fun rebuild(format: AudioProcessor.AudioFormat) {
        dirty = false
        val profile = requested
        if (block.channels != format.channelCount) block = AudioBuffer(format.channelCount, BLOCK_FRAMES)
        processor = profile?.createProcessor()?.also {
            it.prepare(mmm.dsp.AudioFormat(format.sampleRate, format.channelCount, BLOCK_FRAMES))
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 1024
    }
}

/**
 * Lets the spectrum display see what is being played, after the EQ - what actually reaches the ear.
 *
 * Passes audio through untouched and keeps the last couple of seconds as a mono mix, each sample
 * stamped with when it was handed on. The sink plays it some time later - its buffer is a quarter
 * of a second or so - and [SpectrumTap.latest] reads back by that much, so the display moves with
 * the music rather than ahead of it.
 */
class SpectrumTapProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        SpectrumTap.configure(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes / (2 * channels)
        for (i in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) sum += input.short
            SpectrumTap.write(sum / (32768f * channels))
        }
        SpectrumTap.markWritten(SystemClock.elapsedRealtime())

        val output = replaceOutputBuffer(bytes)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        SpectrumTap.clear()
    }
}

/** The ring the tap writes and the display reads; one per process, like the player. */
object SpectrumTap {
    private const val CAPACITY = 1 shl 17

    private val ring = FloatArray(CAPACITY)
    @Volatile private var written = 0L
    @Volatile private var writtenAt = 0L

    @Volatile var sampleRate: Int = 48000
        private set

    /** How far behind the newest sample the one being heard is: the audio sink's buffer. */
    @Volatile var latencyMs: Int = 250

    internal fun configure(rate: Int) {
        sampleRate = rate
    }

    internal fun write(sample: Float) {
        ring[(written and (CAPACITY - 1).toLong()).toInt()] = sample
        written++
    }

    internal fun markWritten(at: Long) {
        writtenAt = at
    }

    internal fun clear() {
        java.util.Arrays.fill(ring, 0f)
    }

    /**
     * Copies the [target].size samples that are playing now into [target].
     * @return false when nothing has been played yet
     */
    fun latest(target: FloatArray): Boolean {
        val end = written
        if (end == 0L) return false
        // Samples written since the last batch arrived keep flowing at the sample rate; the one
        // heard now was written [latencyMs] before the newest, adjusted for time since that batch.
        val sinceWrite = SystemClock.elapsedRealtime() - writtenAt
        val back = ((latencyMs - sinceWrite).coerceAtLeast(0) * sampleRate / 1000)
        val last = (end - back).coerceAtLeast(target.size.toLong())
        val first = last - target.size
        if (end - first > CAPACITY) return false
        for (i in target.indices) target[i] = ring[((first + i) and (CAPACITY - 1).toLong()).toInt()]
        return true
    }
}
