package mmm.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/** Shared measurement helpers, so each test asserts on a physical quantity rather than on samples. */
object TestSupport {

    const val SAMPLE_RATE = 48000

    fun format(frames: Int, channels: Int = 1) = AudioFormat(SAMPLE_RATE, channels, frames)

    /**
     * Steady-state gain a processor applies at [frequencyHz], in dB.
     * The first 20 % of the tone is discarded so filter start-up transients do not pollute the RMS.
     */
    fun probeGainDb(
        processor: AudioProcessor,
        frequencyHz: Double,
        frames: Int = 24000,
        amplitude: Float = 0.25f,
    ): Double {
        val input = SignalGenerator.sine(frequencyHz, frames, SAMPLE_RATE, channels = 1, amplitude = amplitude)
        val output = input.copy()
        processor.prepare(format(frames, 1))
        processor.reset()
        processor.process(output)

        val skip = frames / 5
        return 20.0 * log10(steadyRms(output, skip) / steadyRms(input, skip))
    }

    private fun steadyRms(buffer: AudioBuffer, skip: Int): Double {
        var sum = 0.0
        val c = buffer.data[0]
        for (i in skip until buffer.frames) sum += c[i].toDouble() * c[i]
        return sqrt(sum / (buffer.frames - skip)).coerceAtLeast(1e-12)
    }

    /**
     * Non-fundamental energy of a processed sine relative to the fundamental (THD+N).
     *
     * Everything but DC and the fundamental counts, because quantisation spreads its error across
     * the whole spectrum rather than onto harmonic bins - a pure THD measurement would miss it.
     */
    fun measureThd(
        processor: AudioProcessor,
        fundamentalBin: Int,
        fftSize: Int = 8192,
        amplitude: Float = 0.9f,
    ): Double {
        val frequency = fundamentalBin.toDouble() * SAMPLE_RATE / fftSize
        // Two FFT lengths of signal so the transient can be skipped entirely.
        val frames = fftSize * 2
        val buffer = SignalGenerator.sine(frequency, frames, SAMPLE_RATE, channels = 1, amplitude = amplitude)
        processor.prepare(format(frames, 1))
        processor.reset()
        processor.process(buffer)

        val slice = FloatArray(fftSize)
        buffer.data[0].copyInto(slice, 0, fftSize, fftSize * 2)
        val spectrum = Fft(fftSize).magnitude(slice)

        val fundamental = spectrum[fundamentalBin]
        var residualPower = 0.0
        for (bin in 1 until spectrum.size) {
            if (bin == fundamentalBin) continue
            residualPower += spectrum[bin] * spectrum[bin]
        }
        return sqrt(residualPower) / fundamental.coerceAtLeast(1e-12)
    }

    /** Peak-to-RMS ratio in dB - how "dynamic" a signal still is after compression. */
    fun crestFactorDb(buffer: AudioBuffer): Double =
        20.0 * log10(buffer.peak().toDouble().coerceAtLeast(1e-12) / buffer.rms().coerceAtLeast(1e-12))
}
