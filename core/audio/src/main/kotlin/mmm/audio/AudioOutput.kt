package mmm.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import mmm.dsp.AudioBuffer

/**
 * `AudioTrack` wrapper that writes float PCM on a chosen [OutputRoute].
 *
 * Configured for the low-latency fast mixer path. In live mode everything between the source app
 * and the listener's ears is added delay, and past roughly a quarter of a second the A/B
 * comparison the exercises depend on stops being usable - so the default buffer is deliberately
 * small rather than comfortable.
 */
public class AudioOutput(
    public val sampleRate: Int,
    public val channels: Int,
    public val route: OutputRoute = OutputRoute.MEDIA,
    /**
     * Requested buffer length. Rounded up to the device minimum; 2048 frames is about 42 ms at
     * 48 kHz, which keeps Bluetooth usable without under-running on mid-range hardware.
     */
    requestedBufferFrames: Int = 2048,
) {
    private val channelMask = when (channels) {
        1 -> AndroidAudioFormat.CHANNEL_OUT_MONO
        2 -> AndroidAudioFormat.CHANNEL_OUT_STEREO
        else -> error("unsupported channel count $channels")
    }

    private val minBufferBytes = AudioTrack.getMinBufferSize(
        sampleRate,
        channelMask,
        AndroidAudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(1024)

    public val bufferSizeBytes: Int =
        maxOf(minBufferBytes, requestedBufferFrames * 4 * channels)

    /** Frames of latency this output adds, for the latency readout on the live screen. */
    public val bufferFrames: Int = bufferSizeBytes / (4 * channels)

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(route.usage())
                .setContentType(route.contentType())
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()
        )
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
        )
        .setBufferSizeInBytes(bufferSizeBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    /** The session id other effects can attach to. */
    public val audioSessionId: Int get() = track.audioSessionId

    /** The device this track actually ended up on, for the diagnostics readout. */
    public val routedDevice: AudioDeviceInfo? get() = track.routedDevice

    private val interleaved = FloatArray(bufferFrames * channels)

    /**
     * Pins output to the first available device matching [OutputRoute.preferredDeviceTypes].
     * @return the device chosen, or null if none of the preferred types is connected.
     */
    public fun applyPreferredDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val wanted = route.preferredDeviceTypes()
        if (wanted.isEmpty()) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (type in wanted) {
            val match = devices.firstOrNull { it.type == type } ?: continue
            if (track.setPreferredDevice(match)) return match
        }
        return null
    }

    public fun start() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    public fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    public fun flush() {
        track.pause()
        track.flush()
    }

    /** Blocking write. @return frames actually written, or a negative `AudioTrack` error code. */
    public fun write(buffer: AudioBuffer): Int {
        val samples = buffer.frames * channels
        val target = if (samples <= interleaved.size) interleaved else FloatArray(samples)
        buffer.writeInterleaved(target)
        val written = track.write(target, 0, samples, AudioTrack.WRITE_BLOCKING)
        return if (written < 0) written else written / channels
    }

    public fun setVolume(volume: Float) {
        track.setVolume(volume.coerceIn(0f, 1f))
    }

    public fun release() {
        runCatching { track.stop() }
        track.release()
    }

    public companion object {
        /**
         * The device's preferred output rate. Matching it avoids a resampler in the fast mixer,
         * which is both latency and a (small) sound-quality confound in a listening test.
         */
        public fun preferredSampleRate(audioManager: AudioManager): Int =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        public fun preferredFramesPerBurst(audioManager: AudioManager): Int =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
    }
}
