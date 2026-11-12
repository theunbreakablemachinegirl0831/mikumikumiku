@file:OptIn(UnstableApi::class)

package mmm.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import mmm.dsp.AudioBuffer
import mmm.dsp.harmonics.HarmonicDrive
import mmm.dsp.harmonics.HarmonicProcessor
import mmm.dsp.harmonics.NosRolloff
import mmm.dsp.harmonics.PeakLimiter
import mmm.eq.EqProcessor
import mmm.eq.EqProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The playback chain inside ExoPlayer's audio pipeline: EQ, harmonics, NOS roll-off, limiter.
 *
 * Works on 16-bit PCM because that is what the pipeline hands its processors once float output is
 * off; the samples are processed in float and dithered back to 16 bits.
 *
 * Settings are handed over from the UI thread and picked up on the audio thread at the next
 * buffer, and nothing is ever switched abruptly:
 * - the chain is built from the settings it was given before playback started, so the first
 *   buffer already plays at the level the EQ's preamp sets - never unprocessed first and quieter
 *   a moment later, which is heard as a jump in volume at the start of a song;
 * - an EQ with the same number of filters is updated in place, its preamp gliding;
 * - an EQ of a different shape is built new and crossfaded in over [CROSSFADE_SECONDS];
 * - the harmonics glide to their new gains, and the NOS roll-off fades in and out.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var requested: EqProfile? = null
    @Volatile private var requestedHarmonics: HarmonicDrive = HarmonicDrive.SILENT
    @Volatile private var requestedNos = false
    @Volatile private var dirty = true

    private var eq: EqProcessor? = null
    /** The EQ being faded out, while [fadeLeft] > 0. */
    private var outgoing: EqProcessor? = null
    private var fadeLeft = 0
    private var fadeFrames = 1
    private var harmonics: HarmonicProcessor? = null
    private var harmonicsFor: HarmonicDrive? = null
    private val nos = NosRolloff()
    private val limiter = PeakLimiter()
    private var builtRate = 0
    private var builtChannels = 0
    private var block = AudioBuffer(2, BLOCK_FRAMES)
    private var faded = AudioBuffer(2, BLOCK_FRAMES)
    private val random = Random(0x5EED)

    fun setProfile(profile: EqProfile?) {
        requested = profile
        dirty = true
    }

    fun setHarmonics(drive: HarmonicDrive) {
        requestedHarmonics = drive
        dirty = true
    }

    fun setNos(on: Boolean) {
        requestedNos = on
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
        // The pipeline also calls this with nothing to process - whenever the stage before has
        // produced no output yet - and then hands over its one shared empty buffer. Before any
        // audio has passed, this processor's own output buffer is that same shared buffer, and
        // copying a buffer into itself is an error that stops playback.
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val bytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(bytes)
        if (dirty) refresh(format)

        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        var frames = bytes / (2 * channels)
        while (frames > 0) {
            val count = minOf(frames, BLOCK_FRAMES)
            block.frames = count
            for (i in 0 until count) {
                for (ch in 0 until channels) block.data[ch][i] = input.short / 32768f
            }
            processBlock(count, channels)
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

    private fun processBlock(count: Int, channels: Int) {
        val fading = outgoing
        if (fading != null && fadeLeft > 0) {
            // The old EQ plays a copy; the two are mixed along an equal-gain line.
            faded.frames = count
            for (ch in 0 until channels) System.arraycopy(block.data[ch], 0, faded.data[ch], 0, count)
            fading.process(faded)
            eq?.process(block)
            val start = fadeLeft
            for (ch in 0 until channels) {
                val now = block.data[ch]
                val old = faded.data[ch]
                for (i in 0 until count) {
                    val left = (start - i).coerceAtLeast(0)
                    val w = left.toFloat() / fadeFrames
                    now[i] = now[i] * (1f - w) + old[i] * w
                }
            }
            fadeLeft = (fadeLeft - count).coerceAtLeast(0)
            if (fadeLeft == 0) outgoing = null
        } else {
            eq?.process(block)
        }
        harmonics?.process(block)
        nos.process(block)
        limiter.process(block)
    }

    override fun onFlush() {
        eq?.reset()
        outgoing = null
        fadeLeft = 0
        harmonics?.reset()
        nos.reset()
        limiter.reset()
    }

    override fun onReset() {
        eq = null
        outgoing = null
        fadeLeft = 0
        harmonics = null
        harmonicsFor = null
        builtRate = 0
        dirty = true
    }

    private fun refresh(format: AudioProcessor.AudioFormat) {
        dirty = false
        val profile = requested ?: EqProfile.FLAT
        val drive = requestedHarmonics
        val dsp = mmm.dsp.AudioFormat(format.sampleRate, format.channelCount, BLOCK_FRAMES)
        val fresh = format.sampleRate != builtRate || format.channelCount != builtChannels
        if (fresh) {
            // A new stream: everything is built at the values it should play at, no glides.
            builtRate = format.sampleRate
            builtChannels = format.channelCount
            block = AudioBuffer(format.channelCount, BLOCK_FRAMES)
            faded = AudioBuffer(format.channelCount, BLOCK_FRAMES)
            fadeFrames = (CROSSFADE_SECONDS * format.sampleRate).toInt().coerceAtLeast(1)
            eq = profile.createProcessor().also { it.prepare(dsp) }
            outgoing = null
            fadeLeft = 0
            harmonics = HarmonicProcessor(drive).also { it.prepare(dsp) }
            harmonicsFor = drive
            nos.setEnabled(requestedNos)
            nos.prepare(dsp)
            limiter.prepare(dsp)
            return
        }

        val current = eq
        if (current != null && current.filterCount == profile.filters.size) {
            current.update(profile)
        } else {
            outgoing = current
            fadeLeft = if (current != null) fadeFrames else 0
            eq = profile.createProcessor().also { it.prepare(dsp) }
        }
        if (drive !== harmonicsFor) {
            harmonicsFor = drive
            harmonics?.update(drive)
        }
        nos.setEnabled(requestedNos)
    }

    private companion object {
        const val BLOCK_FRAMES = 1024
        const val CROSSFADE_SECONDS = 0.03
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
        // See EqAudioProcessor.queueInput: an empty call must not touch the shared empty buffer.
        if (!inputBuffer.hasRemaining()) return
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
