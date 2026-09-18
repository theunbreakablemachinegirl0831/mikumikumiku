package mmm.source.file

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import mmm.dsp.AudioBuffer
import java.nio.ByteOrder

/** A decoded excerpt, ready to be handed to the renderer. */
public data class DecodedClip(
    val audio: AudioBuffer,
    val sampleRate: Int,
    val sourceName: String,
) {
    val durationSeconds: Double get() = audio.frames.toDouble() / sampleRate
}

/**
 * Decodes an excerpt of a user-chosen file to planar float PCM.
 *
 * Decoding to memory rather than streaming is what makes the file mode's loudness matching exact:
 * the renderer can measure a whole stimulus, correct it, and measure again, which a live stream
 * can never allow. Excerpts are short by design - a training trial is a few seconds, and holding
 * minutes of float audio to play ten of them would be wasteful.
 *
 * The clip keeps its own sample rate; the file-mode output is opened to match, so nothing is
 * resampled between the file and the listener.
 */
public object ClipDecoder {

    /** Longer than any exercise needs, and a guard against someone picking a two-hour podcast. */
    public const val MAX_EXCERPT_SECONDS: Double = 30.0

    public class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param startSeconds where in the file to begin
     * @param lengthSeconds how much to take, clamped to [MAX_EXCERPT_SECONDS]
     */
    public fun decode(
        context: Context,
        uri: Uri,
        startSeconds: Double = 0.0,
        lengthSeconds: Double = 10.0,
    ): DecodedClip {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("파일을 열 수 없다", e)
        }

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw DecodeException("오디오 트랙이 없다")
        }

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: run {
                extractor.release()
                throw DecodeException("알 수 없는 오디오 형식이다")
            }
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        extractor.selectTrack(trackIndex)
        extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val wanted = lengthSeconds.coerceIn(0.5, MAX_EXCERPT_SECONDS)
        val targetFrames = (wanted * sampleRate).toInt()

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("디코더를 만들 수 없다 ($mime)", e)
        }

        try {
            val collected = decodeLoop(extractor, codec, sourceChannels, targetFrames)
            return DecodedClip(
                audio = collected,
                sampleRate = sampleRate,
                sourceName = uri.lastPathSegment ?: "선택한 음원",
            )
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        sourceChannels: Int,
        targetFrames: Int,
    ): AudioBuffer {
        val output = AudioBuffer(STEREO, targetFrames)
        output.frames = 0
        var written = 0

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pcmEncoding = MediaFormat.ENCODING_PCM_16BIT
        var decodedChannels = sourceChannels

        while (!outputDone && written < targetFrames) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = codec.outputFormat
                    decodedChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> {
                    if (outputIndex < 0) continue
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        written += appendFrames(buffer, output, written, targetFrames, decodedChannels, pcmEncoding)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        if (written == 0) throw DecodeException("디코딩된 오디오가 없다")
        output.frames = written
        return output
    }

    /**
     * Copies one decoded buffer into [output], converting to stereo float.
     * Mono is duplicated and anything wider is folded down to the front pair, because the
     * exercises are stereo and a surround file would otherwise lose its centre entirely.
     */
    private fun appendFrames(
        buffer: java.nio.ByteBuffer,
        output: AudioBuffer,
        offset: Int,
        targetFrames: Int,
        channels: Int,
        pcmEncoding: Int,
    ): Int {
        val room = targetFrames - offset
        if (room <= 0) return 0

        val ordered = buffer.order(ByteOrder.nativeOrder())
        val frames: Int
        when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                frames = minOf(room, floats.remaining() / channels)
                for (f in 0 until frames) {
                    val base = f * channels
                    val left = floats.get(base)
                    val right = if (channels > 1) floats.get(base + 1) else left
                    output.data[0][offset + f] = left
                    output.data[1][offset + f] = right
                }
            }
            else -> {
                val shorts = ordered.asShortBuffer()
                frames = minOf(room, shorts.remaining() / channels)
                for (f in 0 until frames) {
                    val base = f * channels
                    val left = shorts.get(base) / 32768f
                    val right = if (channels > 1) shorts.get(base + 1) / 32768f else left
                    output.data[0][offset + f] = left
                    output.data[1][offset + f] = right
                }
            }
        }
        return frames
    }

    private const val STEREO = 2
    private const val TIMEOUT_US = 10_000L
}
