package mmm.source.usb

import mmm.audio.AudioSink
import mmm.dsp.AudioBuffer
import java.util.concurrent.locks.LockSupport

/**
 * [AudioSink] that feeds a [UsbIsoStreamer], so [mmm.audio.PlaybackEngine] can play to a claimed
 * DAC exactly as it plays to an `AudioTrack`.
 *
 * [write] blocks while the stream's ring is full. For a test tone that is what paces the producer
 * to the bus; for live capture the ring rarely fills, because the drift servo holds it near its
 * target.
 */
public class UsbAudioSink(private val streamer: UsbIsoStreamer) : AudioSink {

    override val sampleRate: Int get() = streamer.sampleRate
    override val channels: Int get() = streamer.channels
    override val bufferFrames: Int get() = streamer.ring.capacityFrames

    private var interleaved = FloatArray(0)

    override fun start() {
        streamer.start()
    }

    /** The bus keeps running; with nothing written, the stream sends silence. */
    override fun pause() {}

    override fun write(buffer: AudioBuffer): Int {
        val frames = buffer.frames
        val samples = frames * channels
        if (interleaved.size < samples) interleaved = FloatArray(samples)
        buffer.writeInterleaved(interleaved)

        var done = 0
        while (done < frames) {
            if (!streamer.isRunning) return if (done > 0) done else -1
            val written = streamer.ring.write(interleaved, frames - done, done)
            done += written
            if (written == 0) LockSupport.parkNanos(WAIT_NANOS)
        }
        return done
    }

    override fun release() {
        streamer.stop()
    }

    override fun attachUpstream(backlogFrames: () -> Int) {
        streamer.upstreamBacklog = backlogFrames
    }

    private companion object {
        /** One packet's worth at full speed; the ring cannot free up faster than the bus drains it. */
        const val WAIT_NANOS = 1_000_000L
    }
}
