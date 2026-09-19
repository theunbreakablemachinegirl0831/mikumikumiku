package mmm.dsp.stream

import mmm.dsp.AudioBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Peak and RMS meter with a decaying peak hold.
 *
 * The diagnostics screen leans on this to answer the one question that matters when live capture
 * comes up empty: a flat -inf dBFS reading means the source app is refusing to be captured, which
 * looks exactly like "the app is broken" unless the meter is on screen to say otherwise.
 */
public class LevelMeter(private val peakDecayPerBlock: Float = 0.92f) {

    @Volatile
    public var rmsDb: Double = SILENCE_DB
        private set

    @Volatile
    public var peakDb: Double = SILENCE_DB
        private set

    private var heldPeak = 0f

    /** True once any block has carried signal above the noise floor. */
    @Volatile
    public var sawSignal: Boolean = false
        private set

    public fun update(buffer: AudioBuffer) {
        if (buffer.frames == 0) return

        var sumSquares = 0.0
        var blockPeak = 0f
        for (ch in 0 until buffer.channels) {
            val c = buffer.data[ch]
            for (i in 0 until buffer.frames) {
                val v = c[i]
                sumSquares += v.toDouble() * v
                val a = abs(v)
                if (a > blockPeak) blockPeak = a
            }
        }

        val rms = sqrt(sumSquares / (buffer.frames * buffer.channels))
        rmsDb = toDb(rms)

        heldPeak = if (blockPeak > heldPeak) blockPeak else heldPeak * peakDecayPerBlock
        peakDb = toDb(heldPeak.toDouble())

        if (blockPeak > SIGNAL_FLOOR) sawSignal = true
    }

    public fun reset() {
        rmsDb = SILENCE_DB
        peakDb = SILENCE_DB
        heldPeak = 0f
        sawSignal = false
    }

    private fun toDb(linear: Double): Double =
        if (linear <= 1e-7) SILENCE_DB else 20.0 * log10(linear)

    public companion object {
        public const val SILENCE_DB: Double = -120.0

        /** About -80 dBFS: above dither and below anything a user would call silence. */
        private const val SIGNAL_FLOOR = 1e-4f
    }
}
