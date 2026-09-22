package mmm.dsp

/**
 * A single stage of the listening-test signal chain.
 *
 * Contract: [prepare] may allocate, [process] and [reset] must not. Every artifact the training
 * engine can impose on a signal is one of these, which is what lets the file mode and the live
 * capture mode share exactly the same processing.
 */
public interface AudioProcessor {

    public fun prepare(format: AudioFormat)

    /** Processes [buffer] in place. Called on the audio thread. */
    public fun process(buffer: AudioBuffer)

    /** Drops filter/delay state without changing parameters. */
    public fun reset()

    /** Extra delay this stage introduces, for chains that need to stay sample-aligned. */
    public val latencyFrames: Int get() = 0
}

/** An [AudioProcessor] that does nothing - the "no artifact" reference stimulus. */
public object PassThroughProcessor : AudioProcessor {
    override fun prepare(format: AudioFormat): Unit = Unit
    override fun process(buffer: AudioBuffer): Unit = Unit
    override fun reset(): Unit = Unit
}

/** Runs [stages] in order over the same buffer. */
public class ProcessorChain(stages: List<AudioProcessor> = emptyList()) : AudioProcessor {

    private val stages: MutableList<AudioProcessor> = stages.toMutableList()
    private var format: AudioFormat? = null

    public fun add(stage: AudioProcessor): ProcessorChain = apply {
        stages += stage
        format?.let(stage::prepare)
    }

    public fun clear() {
        stages.clear()
    }

    public val size: Int get() = stages.size

    override fun prepare(format: AudioFormat) {
        this.format = format
        stages.forEach { it.prepare(format) }
    }

    override fun process(buffer: AudioBuffer) {
        for (i in stages.indices) stages[i].process(buffer)
    }

    override fun reset() {
        stages.forEach(AudioProcessor::reset)
    }

    override val latencyFrames: Int get() = stages.sumOf { it.latencyFrames }
}
