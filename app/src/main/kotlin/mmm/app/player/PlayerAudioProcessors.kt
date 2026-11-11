@file:OptIn(UnstableApi::class)

package mmm.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import mmm.dsp.AudioBuffer
import mmm.dsp.artifacts.TubeProcessor
import mmm.dsp.artifacts.TubeSettings
import mmm.eq.EqProcessor
import mmm.eq.EqProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The playback EQ and the valve harmonics inside ExoPlayer's audio pipeline.
 *
 * Works on 16-bit PCM because that is what the pipeline hands its processors once float output is
 * off; the samples are processed in float and dithered back to 16 bits, so the EQ's preamp - which
 * leaves the loudest boost just under full scale - is what keeps it from clipping.
 *
 * Settings are swapped from the UI thread and picked up on the audio thread at the next buffer.
 * An EQ that only changed its numbers is updated in place, so dragging a band does not click;
 * anything else builds a new chain, which allocates, at the moment the listener changed something.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var requested: EqProfile? = null
    @Volatile private var requestedTube: TubeSettings? = null
    @Volatile private var dirty = true

    private var eq: EqProcessor? = null
    private var tube: TubeProcessor? = null
    private var tubeFor: TubeSettings? = null
    private var builtRate = 0
    private var builtChannels = 0
    private var block = AudioBuffer(2, BLOCK_FRAMES)
    private val random = Random(0x5EED)

    fun setProfile(profile: EqProfile?) {
        requested = profile
        dirty = true
    }

    fun setTube(settings: TubeSettings?) {
        requestedTube = settings
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
        if (dirty) refresh(format)

        val activeEq = eq
        val activeTube = tube
        if (activeEq == null && activeTube == null) {
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
            activeEq?.process(block)
            activeTube?.process(block)
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
        eq?.reset()
        tube?.reset()
    }

    override fun onReset() {
        eq = null
        tube = null
        tubeFor = null
        dirty = true
    }

    private fun refresh(format: AudioProcessor.AudioFormat) {
        dirty = false
        val profile = requested
        val wantedTube = requestedTube
        if (format.sampleRate != builtRate || format.channelCount != builtChannels) {
            builtRate = format.sampleRate
            builtChannels = format.channelCount
            block = AudioBuffer(format.channelCount, BLOCK_FRAMES)
            eq = null
            tube = null
            tubeFor = null
        }
        val dsp = mmm.dsp.AudioFormat(format.sampleRate, format.channelCount, BLOCK_FRAMES)

        val current = eq
        when {
            profile == null -> eq = null
            current != null -> current.update(profile)
            else -> eq = profile.createProcessor().also { it.prepare(dsp) }
        }
        if (wantedTube != tubeFor || (wantedTube != null && tube == null)) {
            tubeFor = wantedTube
            tube = wantedTube?.let { settings -> TubeProcessor(settings).also { it.prepare(dsp) } }
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
