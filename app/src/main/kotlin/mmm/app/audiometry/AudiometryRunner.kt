package mmm.app.audiometry

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mmm.audio.AudioOutput
import mmm.dsp.AudioBuffer
import mmm.training.audiometry.AudiogramResult
import mmm.training.audiometry.AudiometryProtocol
import mmm.training.audiometry.Ear
import mmm.training.audiometry.ThresholdPoint
import mmm.training.audiometry.ThresholdSearch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Where the test is, for the screen. Deliberately says nothing about whether a tone is playing. */
data class AudiometryProgress(
    val ear: Ear,
    val frequencyHz: Double,
    val step: Int,
    val steps: Int,
)

/**
 * Plays the test: pulsed tones to one ear at a time, a response window after each, and a
 * [ThresholdSearch] per frequency deciding the next level.
 *
 * Tones are pulsed - three 250 ms bursts - because a pulsed tone stands out from tinnitus and
 * from the hiss of the playback chain in a way a steady one does not. Gaps before each tone are
 * random, and one presentation in [AudiometryProtocol.CATCH_TRIAL_EVERY] plays nothing at all,
 * so pressing on rhythm shows up as false alarms instead of as good hearing.
 *
 * Plays straight to an `AudioTrack`, never through the earphone EQ: the test measures the
 * earphone as it is, and the earphone's measurement then takes it out of the result.
 */
class AudiometryRunner(
    private val onProgress: (AudiometryProgress) -> Unit,
    private val random: Random = Random(System.nanoTime()),
) {
    @Volatile private var lastPressAt = 0L

    /** Call when the listener presses the button. */
    fun press() {
        lastPressAt = SystemClock.elapsedRealtime()
    }

    suspend fun run(earphoneName: String?): AudiogramResult {
        val output = AudioOutput(RATE, 2)
        output.start()
        val latencyMs = output.bufferFrames * 1000L / RATE
        try {
            val points = ArrayList<ThresholdPoint>()
            var falseAlarms = 0
            var catchTrials = 0
            var presentations = 0
            val steps = Ear.entries.size * AudiometryProtocol.FREQUENCIES.size
            var step = 0

            for (ear in Ear.entries) {
                // Each ear starts where the listener can hear; later frequencies start from the
                // last threshold plus a margin, which shortens the test without biasing it.
                var start = ThresholdSearch.DEFAULT_START_DB
                for (frequency in AudiometryProtocol.FREQUENCIES) {
                    step++
                    onProgress(AudiometryProgress(ear, frequency, step, steps))
                    val search = ThresholdSearch(startDb = start)
                    while (!search.isDone) {
                        presentations++
                        val catch = presentations % AudiometryProtocol.CATCH_TRIAL_EVERY == 0
                        val (heard, pressedEarly) = present(output, latencyMs, ear, frequency, search.levelDb, silent = catch)
                        if (pressedEarly) falseAlarms++
                        if (catch) {
                            catchTrials++
                            if (heard) falseAlarms++
                        } else {
                            search.record(heard)
                        }
                    }
                    points += ThresholdPoint(ear, frequency, search.thresholdDb, search.presentations)
                    search.thresholdDb?.let { start = (it + START_MARGIN_DB).coerceAtMost(ThresholdSearch.DEFAULT_MAX_DB) }
                }
            }

            val retest = Ear.entries.associateWith { ear ->
                val ones = points.filter { it.ear == ear && it.frequencyHz == AudiometryProtocol.RETEST_FREQUENCY_HZ }
                    .mapNotNull { it.thresholdDbfs }
                if (ones.size == 2) abs(ones[0] - ones[1]) else 0.0
            }
            return AudiogramResult(
                points = points,
                falseAlarms = falseAlarms,
                catchTrials = catchTrials,
                retestDifferenceDb = retest,
                earphoneName = earphoneName,
                timestampMillis = System.currentTimeMillis(),
            )
        } finally {
            output.release()
        }
    }

    /**
     * One presentation: a random silent gap, the tone (or nothing), then the response window.
     * @return whether the button was pressed in the window, and whether it was pressed during the gap
     */
    private suspend fun present(
        output: AudioOutput,
        latencyMs: Long,
        ear: Ear,
        frequency: Double,
        levelDb: Double,
        silent: Boolean,
    ): Pair<Boolean, Boolean> {
        val gapMs = GAP_MIN_MS + random.nextLong(GAP_RANGE_MS)
        val gapStart = SystemClock.elapsedRealtime()
        writeSilence(output, gapMs)
        val pressedEarly = lastPressAt in gapStart..SystemClock.elapsedRealtime()

        val toneStart = SystemClock.elapsedRealtime() + latencyMs
        if (silent) writeSilence(output, TONE_MS) else write(output, tone(ear, frequency, levelDb))
        val windowEnd = toneStart + TONE_MS + RESPONSE_MS
        // Keep the stream fed while waiting for the response window to close.
        while (SystemClock.elapsedRealtime() < windowEnd) writeSilence(output, CHUNK_MS)
        val heard = lastPressAt in toneStart..windowEnd
        return heard to pressedEarly
    }

    private fun tone(ear: Ear, frequency: Double, levelDb: Double): AudioBuffer {
        val frames = (TONE_MS * RATE / 1000).toInt()
        val amplitude = (kotlin.math.sqrt(2.0) * 10.0.pow(levelDb / 20)).toFloat()
        val pulse = (PULSE_ON_MS * RATE / 1000).toInt()
        val period = ((PULSE_ON_MS + PULSE_OFF_MS) * RATE / 1000).toInt()
        val ramp = (RAMP_MS * RATE / 1000).toInt()
        val channel = if (ear == Ear.LEFT) 0 else 1
        return AudioBuffer(2, frames).apply {
            this.frames = frames
            val samples = data[channel]
            for (i in 0 until frames) {
                val inPulse = i % period
                if (inPulse >= pulse) continue
                // Raised-cosine ramps: a hard edge splatters energy across the spectrum and is
                // heard as a click at frequencies far from the one being tested.
                val envelope = when {
                    inPulse < ramp -> 0.5 - 0.5 * kotlin.math.cos(PI * inPulse / ramp)
                    inPulse > pulse - ramp -> 0.5 - 0.5 * kotlin.math.cos(PI * (pulse - inPulse) / ramp)
                    else -> 1.0
                }
                samples[i] = (amplitude * envelope * sin(2 * PI * frequency * i / RATE)).toFloat()
            }
        }
    }

    private suspend fun writeSilence(output: AudioOutput, ms: Long) {
        var remaining = (ms * RATE / 1000).toInt()
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val frames = minOf(remaining, CHUNK_FRAMES)
            silence.frames = frames
            output.write(silence)
            remaining -= frames
        }
    }

    private suspend fun write(output: AudioOutput, buffer: AudioBuffer) {
        currentCoroutineContext().ensureActive()
        output.write(buffer)
    }

    private val silence = AudioBuffer(2, CHUNK_FRAMES).apply { clear() }

    private companion object {
        const val RATE = 48000
        const val PULSE_ON_MS = 250L
        const val PULSE_OFF_MS = 200L
        const val RAMP_MS = 20L
        const val TONE_MS = 3 * (PULSE_ON_MS + PULSE_OFF_MS)
        const val RESPONSE_MS = 1500L
        const val GAP_MIN_MS = 1200L
        const val GAP_RANGE_MS = 1800L
        const val CHUNK_MS = 40L
        const val CHUNK_FRAMES = 1920 // CHUNK_MS at RATE
        const val START_MARGIN_DB = 20.0
    }
}
