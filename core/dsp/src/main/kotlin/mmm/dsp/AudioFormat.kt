package mmm.dsp

/**
 * Static description of the stream a [AudioProcessor] is being prepared for.
 *
 * [maxFrames] is the largest block the processor will ever be handed, so processors that need
 * scratch space can allocate once in [AudioProcessor.prepare] and never allocate on the audio
 * thread afterwards.
 */
public data class AudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val maxFrames: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels in 1..8) { "channels must be 1..8, was $channels" }
        require(maxFrames > 0) { "maxFrames must be positive, was $maxFrames" }
    }

    val nyquist: Double get() = sampleRate / 2.0
}
