package mmm.dsp

import kotlin.math.log10

/**
 * The long-term spectrum of a piece of audio: how much energy it carries in each part of the band.
 *
 * Exists for one question - "is there anything here to hear?" A band boost on a band the music
 * barely touches changes nothing audible, so a Band ID question aimed there cannot be answered
 * from the sound, only guessed. Songs make this common: an intro without bass, a lo-fi track
 * rolled off at 10 kHz, a vocal section with nothing below 150 Hz.
 *
 * Measured as Welch-averaged power (Hann windows, half overlap, all channels), then read out as
 * energy per octave, so bands of different widths compare fairly.
 */
public class SpectrumProfile private constructor(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val power: DoubleArray,
) {
    /** Energy density between [lowHz] and [highHz], in dB per octave (relative, uncalibrated). */
    public fun densityDb(lowHz: Double, highHz: Double): Double {
        val binWidth = sampleRate.toDouble() / fftSize
        val from = (lowHz / binWidth).toInt().coerceIn(1, power.lastIndex)
        val to = (highHz / binWidth).toInt().coerceIn(from, power.lastIndex)
        var sum = 0.0
        for (bin in from..to) sum += power[bin]
        val octaves = (kotlin.math.ln(highHz / lowHz) / kotlin.math.ln(2.0)).coerceAtLeast(1e-6)
        return 10 * log10((sum / octaves).coerceAtLeast(1e-30))
    }

    /**
     * What "well filled" means for this audio: the 75th percentile of its third-octave densities
     * from 63 Hz to 12.5 kHz. A percentile rather than the maximum, so one booming bass note does
     * not make the rest of the spectrum look empty.
     */
    public val referenceDb: Double by lazy {
        val densities = BandGrid(BandResolution.THIRD_OCTAVE, 63.0, 12500.0).bands
            .map { densityDb(it.lowerHz, it.upperHz) }
            .sorted()
        densities[(densities.size * 3 / 4).coerceAtMost(densities.lastIndex)]
    }

    /** True when the audio has enough energy between [lowHz] and [highHz] for a change there to be heard. */
    public fun isUsable(lowHz: Double, highHz: Double, marginDb: Double = DEFAULT_MARGIN_DB): Boolean =
        densityDb(lowHz, highHz) >= referenceDb - marginDb

    /** How many third-octave bands from 40 Hz to 16 kHz are usable: a coverage score for picking excerpts. */
    public fun coverage(marginDb: Double = DEFAULT_MARGIN_DB): Int =
        BandGrid(BandResolution.THIRD_OCTAVE, 40.0, 16000.0).bands.count { isUsable(it.lowerHz, it.upperHz, marginDb) }

    public companion object {
        /**
         * How far below the well-filled level a band may sit and still carry a boost or cut you
         * can hear. Generous on purpose: real music falls off steeply in the top octaves, and a
         * margin tight enough to exclude genuinely empty bands must not also exclude ordinary treble.
         */
        public const val DEFAULT_MARGIN_DB: Double = 30.0

        private const val FFT_SIZE = 8192

        public fun analyze(
            audio: AudioBuffer,
            sampleRate: Int,
            fromFrame: Int = 0,
            frames: Int = audio.frames - fromFrame,
        ): SpectrumProfile {
            val size = FFT_SIZE
            val fft = Fft(size)
            val window = Window.hann(size)
            val power = DoubleArray(size / 2 + 1)
            val re = DoubleArray(size)
            val im = DoubleArray(size)
            val end = (fromFrame + frames).coerceAtMost(audio.frames)
            var blocks = 0
            var start = fromFrame
            while (start + size <= end) {
                for (ch in 0 until audio.channels) {
                    val samples = audio.data[ch]
                    for (i in 0 until size) {
                        re[i] = samples[start + i] * window[i].toDouble()
                        im[i] = 0.0
                    }
                    fft.forward(re, im)
                    for (bin in power.indices) power[bin] += re[bin] * re[bin] + im[bin] * im[bin]
                }
                blocks++
                start += size / 2
            }
            if (blocks > 0) {
                val scale = 1.0 / (blocks * audio.channels)
                for (bin in power.indices) power[bin] *= scale
            }
            return SpectrumProfile(sampleRate, size, power)
        }

        /**
         * The start frame of the [lengthFrames]-long excerpt of [audio] whose spectrum is most
         * complete, checked every [hopSeconds]. Ties go to the louder excerpt, so a quiet
         * breakdown does not win over the chorus on a technicality.
         *
         * Every analysis block is transformed once and reduced to third-octave powers; each
         * candidate excerpt is then just a sum over its blocks. Re-analysing every candidate from
         * scratch would cost a minute of FFTs per candidate, which is seconds on a phone.
         */
        public fun bestExcerptStart(
            audio: AudioBuffer,
            sampleRate: Int,
            lengthFrames: Int,
            hopSeconds: Double = 1.0,
        ): Int {
            if (audio.frames <= lengthFrames) return 0
            val bands = BandGrid(BandResolution.THIRD_OCTAVE, 40.0, 16000.0).bands
            val reference = bands.indices.filter { bands[it].centerHz in 63.0..12500.0 }
            val octaves = DoubleArray(bands.size) { ln2(bands[it].upperHz / bands[it].lowerHz) }
            val blockHop = FFT_SIZE / 2
            val blocks = bandPowersPerBlock(audio, sampleRate, bands)

            val hop = (hopSeconds * sampleRate).toInt().coerceAtLeast(1)
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            val sums = DoubleArray(bands.size)
            var start = 0
            while (start + lengthFrames <= audio.frames) {
                sums.fill(0.0)
                val first = (start + blockHop - 1) / blockHop
                var block = first
                while (block < blocks.size && block * blockHop + FFT_SIZE <= start + lengthFrames) {
                    val powers = blocks[block]
                    for (b in sums.indices) sums[b] += powers[b]
                    block++
                }
                if (block > first) {
                    val densities = DoubleArray(bands.size) { 10 * log10((sums[it] / octaves[it]).coerceAtLeast(1e-30)) }
                    val sorted = reference.map { densities[it] }.sorted()
                    val ref = sorted[(sorted.size * 3 / 4).coerceAtMost(sorted.lastIndex)]
                    val coverage = densities.count { it >= ref - DEFAULT_MARGIN_DB }
                    val total = sums.sum() / (block - first)
                    // Coverage dominates; loudness only breaks ties (it is worth under one band).
                    val score = coverage + 0.5 * (total / (1.0 + total))
                    if (score > bestScore) {
                        bestScore = score
                        best = start
                    }
                }
                start += hop
            }
            return best
        }

        /** Third-octave powers of every half-overlapping Hann block of [audio], all channels summed. */
        private fun bandPowersPerBlock(audio: AudioBuffer, sampleRate: Int, bands: List<Band>): List<DoubleArray> {
            val size = FFT_SIZE
            val fft = Fft(size)
            val window = Window.hann(size)
            val binWidth = sampleRate.toDouble() / size
            val ranges = bands.map { band ->
                val from = (band.lowerHz / binWidth).toInt().coerceIn(1, size / 2)
                from..(band.upperHz / binWidth).toInt().coerceIn(from, size / 2)
            }
            val re = DoubleArray(size)
            val im = DoubleArray(size)
            val result = ArrayList<DoubleArray>()
            var start = 0
            while (start + size <= audio.frames) {
                val powers = DoubleArray(bands.size)
                for (ch in 0 until audio.channels) {
                    val samples = audio.data[ch]
                    for (i in 0 until size) {
                        re[i] = samples[start + i] * window[i].toDouble()
                        im[i] = 0.0
                    }
                    fft.forward(re, im)
                    ranges.forEachIndexed { b, range ->
                        var sum = 0.0
                        for (bin in range) sum += re[bin] * re[bin] + im[bin] * im[bin]
                        powers[b] += sum
                    }
                }
                result += powers
                start += size / 2
            }
            return result
        }

        private fun ln2(x: Double): Double = kotlin.math.ln(x) / kotlin.math.ln(2.0)
    }
}
