package mmm.source.capture

import mmm.audio.OutputRoute

/** Lifecycle of the live capture pipeline, as the UI sees it. */
public sealed interface CaptureState {

    public data object Idle : CaptureState

    public data object Starting : CaptureState

    public data class Running(
        val sampleRate: Int,
        val channels: Int,
        val route: OutputRoute,
        /** Total added delay from capture in to speaker out, in milliseconds. */
        val latencyMs: Int,
        /** The device our processed output actually landed on, if we could tell. */
        val outputDevice: String?,
    ) : CaptureState

    public data class Failed(val reason: String) : CaptureState
}

/**
 * Live numbers for the diagnostics screen.
 *
 * [inputRmsDb] is the important one: when a source app refuses to be captured the API hands us a
 * perfectly valid stream of zeroes rather than an error, so an empty meter is the only signal that
 * anything is wrong.
 */
public data class CaptureTelemetry(
    val inputRmsDb: Double = -120.0,
    val inputPeakDb: Double = -120.0,
    val outputRmsDb: Double = -120.0,
    val sawSignal: Boolean = false,
    val underrunFrames: Long = 0,
    val overrunFrames: Long = 0,
    val framesProcessed: Long = 0,
    val levelCorrectionDb: Double = 0.0,
    val error: String? = null,
) {
    /**
     * True when capture has been running long enough to be sure, and nothing has arrived.
     * @param runningMs how long the pipeline has been up
     */
    public fun looksBlocked(runningMs: Long): Boolean =
        runningMs > SILENCE_VERDICT_MS && !sawSignal && framesProcessed > 0

    public companion object {
        /** Long enough to rule out a paused track, short enough not to leave the user guessing. */
        public const val SILENCE_VERDICT_MS: Long = 4000
    }
}
