package mmm.audio

import android.os.Process
import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.AudioProcessor
import mmm.dsp.PassThroughProcessor
import mmm.dsp.RealtimeLevelMatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Pulls from an [AudioSource], runs the current artifact chain and writes to an [AudioOutput].
 *
 * The chain is swapped through an [AtomicReference] rather than mutated, so switching stimulus A to
 * stimulus B never leaves the audio thread holding a half-configured filter. The outgoing
 * processor's state is simply dropped, which is correct: each stimulus is meant to be heard from a
 * clean filter state.
 */
public class PlaybackEngine(
    private val source: AudioSource,
    private val output: AudioOutput,
    private val blockFrames: Int = 512,
) {
    private val format = AudioFormat(output.sampleRate, output.channels, blockFrames)
    private val buffer = AudioBuffer(output.channels, blockFrames)

    private val pending = AtomicReference<AudioProcessor?>(null)
    private var active: AudioProcessor = PassThroughProcessor

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Set by [setArtifact]; reported so the UI can show how much level correction is in play. */
    @Volatile
    public var levelMatcher: RealtimeLevelMatch? = null
        private set

    /** Non-fatal problems the UI should surface (under-runs, write errors). */
    @Volatile
    public var lastError: String? = null
        private set

    /** Level before processing - on the live path, this is what says whether capture is working. */
    public val inputMeter: LevelMeter = LevelMeter()

    /** Level after processing, so the level-matching can be seen to be doing its job. */
    public val outputMeter: LevelMeter = LevelMeter()

    @Volatile
    public var framesProcessed: Long = 0L
        private set

    /** Latency this engine contributes, in frames: one block in flight plus the output buffer. */
    public val latencyFrames: Int get() = blockFrames + output.bufferFrames

    public var onSourceExhausted: (() -> Unit)? = null

    /**
     * Installs the processing for the stimulus now being auditioned.
     *
     * @param levelMatched wraps the processor in a live loudness matcher. On by default: without it
     *   a boosted band is also a louder band, and the learner can answer on level alone.
     */
    public fun setArtifact(processor: AudioProcessor, levelMatched: Boolean = true) {
        val staged: AudioProcessor = if (levelMatched) {
            RealtimeLevelMatch(processor).also { levelMatcher = it }
        } else {
            levelMatcher = null
            processor
        }
        staged.prepare(format)
        pending.set(staged)
    }

    public fun clearArtifact() {
        levelMatcher = null
        pending.set(PassThroughProcessor)
    }

    public fun start() {
        if (running) return
        inputMeter.reset()
        outputMeter.reset()
        running = true
        output.start()
        worker = thread(name = "mmm-playback", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            loop()
        }
    }

    public fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        output.pause()
    }

    public fun release() {
        stop()
        output.release()
        source.close()
    }

    private fun loop() {
        while (running) {
            pending.getAndSet(null)?.let { active = it }

            when (val frames = source.read(buffer)) {
                -1 -> {
                    running = false
                    onSourceExhausted?.invoke()
                    return
                }
                0 -> {
                    // Live source has nothing yet. Sleeping for a fraction of a block keeps the
                    // thread responsive without spinning a core flat.
                    Thread.sleep(2)
                    continue
                }
                else -> {
                    inputMeter.update(buffer)
                    active.process(buffer)
                    outputMeter.update(buffer)
                    framesProcessed += frames
                    val written = output.write(buffer)
                    if (written < 0) {
                        lastError = "AudioTrack write failed ($written)"
                        running = false
                        return
                    }
                    if (written < frames) lastError = "Output buffer overflowed"
                }
            }
        }
    }
}
