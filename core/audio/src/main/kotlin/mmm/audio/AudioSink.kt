package mmm.audio

import mmm.dsp.AudioBuffer

/**
 * Where [PlaybackEngine] sends processed audio.
 *
 * Two implementations: [AudioOutput] plays through Android's mixer, and the USB sink streams
 * straight to a DAC the app has claimed. The live mode needs the second, because a claimed DAC is
 * the only output Android's own playback cannot also reach.
 */
public interface AudioSink {
    public val sampleRate: Int
    public val channels: Int

    /** Frames of latency this sink adds, for the latency readout. */
    public val bufferFrames: Int

    public fun start()

    public fun pause()

    /** Blocking write. @return frames written, or a negative error code once the sink has failed. */
    public fun write(buffer: AudioBuffer): Int

    public fun release()

    /**
     * Tells the sink how much audio is waiting upstream of it. A sink that has to keep its own
     * clock in step with the producer's measures the whole backlog, not only its own share.
     */
    public fun attachUpstream(backlogFrames: () -> Int) {}
}
