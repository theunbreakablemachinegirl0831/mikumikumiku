package mmm.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * In-place radix-2 FFT with pre-computed twiddles.
 *
 * Used by the live spectrum display and, in tests, to verify that each artifact processor actually
 * does to the spectrum what its parameters claim.
 */
public class Fft(public val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    /** Forward transform of [re]/[im], both of length [size], in place. */
    public fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "input arrays must be of length $size" }

        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= size) {
            val step = size / len
            val half = len / 2
            var i = 0
            while (i < size) {
                var k = 0
                for (n in i until i + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val m = n + half
                    val xr = re[m] * wr - im[m] * wi
                    val xi = re[m] * wi + im[m] * wr
                    re[m] = re[n] - xr
                    im[m] = im[n] - xi
                    re[n] += xr
                    im[n] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum (bins `0..size/2`) of a real signal, normalised by [size]. */
    public fun magnitude(signal: FloatArray): DoubleArray {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val n = minOf(signal.size, size)
        for (i in 0 until n) re[i] = signal[i].toDouble()
        forward(re, im)
        return DoubleArray(size / 2 + 1) { hypot(re[it], im[it]) / size }
    }

    public fun magnitudeDb(signal: FloatArray, floorDb: Double = -140.0): DoubleArray =
        magnitude(signal).map { (20.0 * log10(it.coerceAtLeast(1e-12))).coerceAtLeast(floorDb) }.toDoubleArray()

    public fun binToFrequency(bin: Int, sampleRate: Int): Double = bin.toDouble() * sampleRate / size

    public fun frequencyToBin(frequency: Double, sampleRate: Int): Int =
        (frequency * size / sampleRate).toInt().coerceIn(0, size / 2)
}

/** Analysis windows. Hann is the default everywhere we do spectral measurement. */
public object Window {
    public fun hann(n: Int): FloatArray =
        FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }

    public fun applyInPlace(signal: FloatArray, window: FloatArray) {
        val n = minOf(signal.size, window.size)
        for (i in 0 until n) signal[i] *= window[i]
    }
}
