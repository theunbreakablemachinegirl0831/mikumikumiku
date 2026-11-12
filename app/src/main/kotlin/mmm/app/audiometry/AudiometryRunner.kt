package mmm.app.audiometry

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mmm.audio.AudioOutput
import mmm.dsp.AudioBuffer
import mmm.training.audiometry.AudiogramResult
import mmm.training.audiometry.AudiometryProtocol
import mmm.training.audiometry.CountingProtocol
import mmm.training.audiometry.CountingTrial
import mmm.training.audiometry.Ear
import mmm.training.audiometry.ThresholdPoint
import mmm.training.audiometry.ThresholdSearch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the listener is being asked to do right now. */
enum class AudiometryPhase { LISTENING, ANSWERING }

/** Where the test is, for the screen. Says nothing about how many pulses were played. */
data class AudiometryProgress(
    val ear: Ear,
    val frequencyHz: Double,
    val step: Int,
    val steps: Int,
    val phase: AudiometryPhase,
    /** When the listening window began, on the elapsed-realtime clock, for the countdown. */
    val listeningSince: Long,
    val trial: Int,
)

/**
 * Plays the counting test (see [CountingProtocol]): for each frequency and ear, five-second
 * windows of one to five tone pulses, a [ThresholdSearch] deciding each window's level from
 * whether the last count was right.
 *
 * Plays straight to an `AudioTrack`, never through the playback EQ: the test measures the
 * earphone as it is, and the earphone's measurement then takes it out of the result.
 */
class AudiometryRunner(
    private val onProgress: (AudiometryProgress) -> Unit,
    private val random: Random = Random(System.nanoTime()),
) {
    private val answers = Channel<Int>(Channel.CONFLATED)

    /** The listener's count for the window just played. */
    fun answer(count: Int) {
        answers.trySend(count)
    }

    suspend fun run(earphoneName: String?): AudiogramResult {
        val output = AudioOutput(RATE, 2)
        output.start()
        val latencyMs = output.bufferFrames * 1000L / RATE
        try {
            val points = ArrayList<ThresholdPoint>()
            var falseAlarms = 0
            var trials = 0
            val steps = Ear.entries.size * AudiometryProtocol.FREQUENCIES.size
            var step = 0

            for (ear in Ear.entries) {
                // Later frequencies start from the last threshold plus a margin, which shortens
                // the test without biasing it.
                var start = ThresholdSearch.DEFAULT_START_DB
                for (frequency in AudiometryProtocol.FREQUENCIES) {
                    step++
                    val search = ThresholdSearch(startDb = start)
                    var trialHere = 0
                    while (!search.isDone) {
                        trialHere++
                        trials++
                        val trial = CountingProtocol.trial(random)
                        // The countdown starts when the window reaches the ear, not when it is queued.
                        onProgress(
                            AudiometryProgress(ear, frequency, step, steps, AudiometryPhase.LISTENING, SystemClock.elapsedRealtime() + latencyMs, trialHere)
                        )
                        play(output, trial, ear, frequency, search.levelDb)
                        // Let the end of the window drain out before asking.
                        writeSilence(output, latencyMs)
                        // Taps during the window are not answers.
                        while (answers.tryReceive().isSuccess) Unit
                        onProgress(AudiometryProgress(ear, frequency, step, steps, AudiometryPhase.ANSWERING, 0L, trialHere))
                        val answer = awaitAnswer(output)
                        val judgement = CountingProtocol.judge(trial, answer)
                        if (judgement.falseAlarm) falseAlarms++
                        search.record(judgement.heard)
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
                // Every window is a chance to report what was not there.
                catchTrials = trials,
                retestDifferenceDb = retest,
                earphoneName = earphoneName,
                timestampMillis = System.currentTimeMillis(),
            )
        } finally {
            output.release()
        }
    }

    /** Waits for the count, keeping the stream fed with silence so the next window starts cleanly. */
    private suspend fun awaitAnswer(output: AudioOutput): Int {
        while (true) {
            answers.tryReceive().getOrNull()?.let { return it }
            writeSilence(output, CHUNK_MS)
        }
    }

    /** The five-second window, made a chunk at a time so a stop is heard at once. */
    private suspend fun play(output: AudioOutput, trial: CountingTrial, ear: Ear, frequency: Double, levelDb: Double) {
        val amplitude = sqrt(2.0) * 10.0.pow(levelDb / 20)
        val channel = if (ear == Ear.LEFT) 0 else 1
        val total = (CountingProtocol.WINDOW_MS * RATE / 1000).toInt()
        val pulse = (CountingProtocol.PULSE_MS * RATE / 1000).toInt()
        val ramp = (RAMP_MS * RATE / 1000).toInt()
        val onsets = trial.onsetsMs.map { (it * RATE / 1000).toInt() }
        var frame = 0
        while (frame < total) {
            currentCoroutineContext().ensureActive()
            val count = minOf(CHUNK_FRAMES, total - frame)
            chunk.clear()
            chunk.frames = count
            val samples = chunk.data[channel]
            for (i in 0 until count) {
                val at = frame + i
                val onset = onsets.firstOrNull { at >= it && at < it + pulse } ?: continue
                val inPulse = at - onset
                // Raised-cosine ramps: a hard edge splatters energy across the spectrum and is
                // heard as a click at frequencies far from the one being tested.
                val envelope = when {
                    inPulse < ramp -> 0.5 - 0.5 * cos(PI * inPulse / ramp)
                    inPulse > pulse - ramp -> 0.5 - 0.5 * cos(PI * (pulse - inPulse) / ramp)
                    else -> 1.0
                }
                samples[i] = (amplitude * envelope * sin(2 * PI * frequency * at / RATE)).toFloat()
            }
            output.write(chunk)
            frame += count
        }
    }

    private suspend fun writeSilence(output: AudioOutput, ms: Long) {
        var remaining = (ms * RATE / 1000).toInt()
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val frames = minOf(remaining, CHUNK_FRAMES)
            chunk.clear()
            chunk.frames = frames
            output.write(chunk)
            remaining -= frames
        }
    }

    private val chunk = AudioBuffer(2, CHUNK_FRAMES)

    private companion object {
        const val RATE = 48000
        const val RAMP_MS = 20L
        const val CHUNK_MS = 40L
        const val CHUNK_FRAMES = 1920 // CHUNK_MS at RATE
        const val START_MARGIN_DB = 20.0
    }
}
