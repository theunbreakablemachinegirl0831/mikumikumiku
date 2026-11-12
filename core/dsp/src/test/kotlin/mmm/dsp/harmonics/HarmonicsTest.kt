package mmm.dsp.harmonics

import mmm.dsp.AudioBuffer
import mmm.dsp.AudioFormat
import mmm.dsp.Fft
import mmm.dsp.SignalGenerator
import mmm.dsp.TestSupport
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonicProfileTest {

    @Test
    fun `H2 and H3 are what they are set to and independent of each other`() {
        val p = HarmonicProfile(h2Db = -4.0, h3Db = -11.0, even = GroupParams(), odd = GroupParams())
        assertEquals(-4.0, p.levelDb(2), 1e-9)
        assertEquals(-11.0, p.levelDb(3), 1e-9)
        val moved = p.copy(h3Db = -2.0)
        assertEquals(-4.0, moved.levelDb(2), 1e-9)
        assertEquals(-4.0, moved.levelDb(4) - p.levelDb(4) + p.levelDb(2), 1e-9, "even orders follow H2 only")
    }

    @Test
    fun `every algorithm falls monotonically from its anchor and stops at the max order`() {
        for (algorithm in HarmonicAlgorithm.entries) {
            val p = HarmonicProfile(h2Db = 0.0, h3Db = 0.0, algorithm = algorithm, maxOrder = 12)
            val even = (2..12 step 2).map { p.levelDb(it) }
            val odd = (3..11 step 2).map { p.levelDb(it) }
            assertTrue(even.zipWithNext().all { (a, b) -> b <= a + 1e-9 }, "$algorithm even $even")
            assertTrue(odd.zipWithNext().all { (a, b) -> b <= a + 1e-9 }, "$algorithm odd $odd")
            assertEquals(HarmonicProfile.OFF, p.levelDb(13), "$algorithm past max order")
            // The whole decay is the slope times the orders spanned, plus the tail's intensity.
            val tail = p.tail
            assertEquals(-tail.slopeDb * 10 + 20 * log10(tail.intensity), p.levelDb(12), 1e-6, "$algorithm at top")
        }
    }

    @Test
    fun `the algorithms differ where it matters, around the centre`() {
        val mid = HarmonicAlgorithm.entries.map { HarmonicProfile(algorithm = it, maxOrder = 14).generatedDb(6) }
        assertEquals(mid.size, mid.map { (it * 100).toInt() }.distinct().size, "curves should not coincide: $mid")
    }

    @Test
    fun `the curves are 0 at the start, 1 at the end and never decrease`() {
        for (algorithm in HarmonicAlgorithm.entries) for (shape in listOf(0.2, 1.0, 5.0)) for (centre in listOf(0.0, 0.4, 1.0)) {
            assertEquals(0.0, algorithm.curve(0.0, centre, shape), 1e-9)
            assertEquals(1.0, algorithm.curve(1.0, centre, shape), 1e-9)
            val values = (0..50).map { algorithm.curve(it / 50.0, centre, shape) }
            assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 1e-12 }, "$algorithm $shape $centre")
        }
    }

    @Test
    fun `groups move only their own parity`() {
        val base = HarmonicProfile(maxOrder = 10)
        val louderEven = base.copy(even = GroupParams(levelDb = -5.0))
        for (n in 3..9 step 2) assertEquals(base.levelDb(n), louderEven.levelDb(n), 1e-9)
        for (n in 2..10 step 2) assertEquals(base.levelDb(n) - 5.0, louderEven.levelDb(n), 1e-9)
        val tiltedOdd = base.copy(odd = GroupParams(slopeDb = -7.0))
        assertEquals(base.levelDb(3), tiltedOdd.levelDb(3), 1e-9)
        assertEquals(base.levelDb(9) - 7.0, tiltedOdd.levelDb(9), 1e-9)
        for (n in 2..10 step 2) assertEquals(base.levelDb(n), tiltedOdd.levelDb(n), 1e-9)
    }

    @Test
    fun `an override replaces one order and nothing else`() {
        val base = HarmonicProfile(maxOrder = 10)
        val edited = base.copy(overrides = mapOf(5 to -2.5))
        assertEquals(-2.5, edited.levelDb(5), 1e-9)
        for (n in (2..10) - 5) assertEquals(base.levelDb(n), edited.levelDb(n), 1e-9)
    }

    @Test
    fun `balance tips between parities in linear amplitude`() {
        val p = HarmonicProfile(h2Db = -6.0, h3Db = -6.0, maxOrder = 3, balance = 0.5)
        val a = p.relativeAmplitudes()
        val half = 10.0.pow(-6.0 / 20)
        assertEquals(half, a[2], 1e-9)
        assertEquals(half * 0.5, a[3], 1e-9)
        val onlyOdd = p.copy(balance = -1.0).relativeAmplitudes()
        assertEquals(0.0, onlyOdd[2], 1e-12)
    }

    @Test
    fun `the output limit caps all harmonics together without changing their proportions`() {
        val free = HarmonicProfile(h2Db = 0.0, h3Db = 0.0, maxOrder = 3, algorithm = HarmonicAlgorithm.LINEAR)
        val limited = free.copy(tails = free.tails + (HarmonicAlgorithm.LINEAR to free.tail.copy(outputLimitDb = -6.0)))
        val a = free.relativeAmplitudes()
        val b = limited.relativeAmplitudes()
        val total = kotlin.math.sqrt(b.sumOf { it * it })
        assertEquals(10.0.pow(-6.0 / 20), total, 1e-9)
        assertEquals(a[2] / a[3], b[2] / b[3], 1e-9)
    }

    @Test
    fun `intensity scales amplitudes so every ratio is kept, and 0 percent is off`() {
        val full = HarmonicSettings(intensityPercent = 100).drive().gains
        val half = HarmonicSettings(intensityPercent = 50).drive().gains
        for (n in 2..HarmonicSettings.MAX_ORDER) {
            if (full[n] == 0.0) continue
            assertEquals(0.5, half[n] / full[n], 1e-12, "order $n")
        }
        assertTrue(HarmonicSettings(intensityPercent = 0).drive().silent)
        // 100 % of a 0 dB H2 is the reference level below full scale.
        val top = HarmonicSettings(profile = HarmonicProfile(h2Db = -6.0, h3Db = -15.0, maxOrder = 3, balance = 0.0)).drive().gains
        assertEquals(10.0.pow((HarmonicSettings.REFERENCE_DB - 6.0) / 20), top[2], 1e-12)
    }

    @Test
    fun `presets have their character`() {
        val triode = HarmonicPreset.TRIODE.profile!!
        assertTrue(triode.levelDb(2) > triode.levelDb(3) + 6, "triode leads with H2")
        assertTrue(triode.levelDb(4) < triode.levelDb(2) && triode.levelDb(6) < triode.levelDb(4), "upper even orders fall away")
        val pushPull = HarmonicPreset.PUSH_PULL.profile!!
        assertTrue(abs(pushPull.levelDb(2) - pushPull.levelDb(3)) <= 4, "push-pull carries both")
        val r2r = HarmonicPreset.R2R.profile!!
        val steps = (2..r2r.maxOrder).map { r2r.levelDb(it) }.zipWithNext { a, b -> a - b }
        assertTrue(steps.all { it < 6 }, "R-2R falls gently: $steps")
        assertTrue(HarmonicPreset.R2R_NOS.nos && !HarmonicPreset.R2R.nos)
        assertEquals(HarmonicPreset.R2R.profile, HarmonicPreset.R2R_NOS.profile)
    }

    @Test
    fun `editing marks the settings custom and keeps the amount`() {
        val s = HarmonicSettings(intensityPercent = 40).withPreset(HarmonicPreset.PUSH_PULL)
        assertEquals(HarmonicPreset.PUSH_PULL, s.preset)
        val edited = s.edit { it.copy(overrides = mapOf(4 to -1.0)) }
        assertEquals(HarmonicPreset.CUSTOM, edited.preset)
        assertEquals(40, edited.intensityPercent)
        assertTrue(HarmonicSettings().withPreset(HarmonicPreset.R2R_NOS).nos)
    }

    @Test
    fun `control values are clamped to the range and the 0_1 dB grid`() {
        assertEquals(-15.0, HarmonicSettings.clampDb(-20.0))
        assertEquals(0.0, HarmonicSettings.clampDb(3.0))
        assertEquals(-3.2, HarmonicSettings.clampDb(-3.2400001))
        assertEquals(-3.3, HarmonicSettings.clampDb(-3.26))
        assertEquals("0.0", HarmonicSettings.clampDb(-0.01).toString())
    }
}

class HarmonicProcessorTest {

    private val fftSize = 8192
    private val rate = TestSupport.SAMPLE_RATE

    /** Levels in dBFS at bins [bin] times 1..[orders] after the processor. */
    private fun spectrum(drive: HarmonicDrive, bin: Int, amplitude: Float, orders: Int = 6): List<Double> {
        // Long enough for the bass band's amplitude follower to settle before the measured slice.
        val frames = fftSize * SETTLE
        val buffer = SignalGenerator.sine(bin.toDouble() * rate / fftSize, frames, rate, channels = 1, amplitude = amplitude)
        val processor = HarmonicProcessor(drive)
        processor.prepare(TestSupport.format(frames, 1))
        processor.process(buffer)
        val slice = FloatArray(fftSize)
        buffer.data[0].copyInto(slice, 0, fftSize * (SETTLE - 1), fftSize * SETTLE)
        val magnitude = Fft(fftSize).magnitude(slice)
        return (1..orders).map { n ->
            val b = bin * n
            if (b >= magnitude.size) -240.0 else 20 * log10((magnitude[b] * 2).coerceAtLeast(1e-12))
        }
    }

    private fun drive(vararg levels: Pair<Int, Double>): HarmonicDrive {
        val gains = DoubleArray(HarmonicSettings.MAX_ORDER + 1)
        for ((n, db) in levels) gains[n] = 10.0.pow(db / 20)
        return HarmonicDrive(gains, doubleArrayOf(1.0, 1.0, 1.0))
    }

    // Bin 11 of 8192 at 48 kHz is 64.5 Hz: well inside the bass band, which may make every
    // order. Near a band edge the band filter would take part of the tone away, and the
    // harmonics, growing as its n-th power, would lose n times as much.
    private val bass = 11
    private val SETTLE = 8

    @Test
    fun `a full-scale tone grows each order at the level set`() {
        val levels = spectrum(drive(2 to -30.0, 3 to -36.0, 4 to -42.0, 5 to -48.0, 6 to -54.0), bass, 1.0f)
        assertEquals(0.0, levels[0], 0.3)
        assertEquals(-30.0, levels[1], 1.0)
        assertEquals(-36.0, levels[2], 1.0)
        assertEquals(-42.0, levels[3], 1.0)
        assertEquals(-48.0, levels[4], 1.0)
        assertEquals(-54.0, levels[5], 1.0)
    }

    @Test
    fun `nothing set means nothing added`() {
        val levels = spectrum(HarmonicDrive.SILENT, bass, 0.5f)
        assertEquals(-6.02, levels[0], 0.05)
        assertTrue(levels.drop(1).all { it < -120 }, "$levels")
    }

    @Test
    fun `quieter music grows proportionally less, n dB per dB for order n`() {
        val loud = spectrum(drive(2 to -20.0, 4 to -20.0), bass, 1.0f)
        val quiet = spectrum(drive(2 to -20.0, 4 to -20.0), bass, 0.1f)
        assertEquals(loud[1] - 40.0, quiet[1], 1.0)
        assertEquals(loud[3] - 80.0, quiet[3], 1.5)
    }

    @Test
    fun `the treble band only makes orders that stay under Nyquist`() {
        // 9 kHz (bin 1536) is above the treble band's shaping ceiling; even if a little leaks
        // through the band filter, no order beyond the third is made from it.
        val levels = spectrum(drive(2 to 0.0, 3 to 0.0, 4 to 0.0, 5 to 0.0), 1536, 0.5f, orders = 2)
        assertEquals(-6.02, levels[0], 0.5)
        // Its 5th harmonic would fold to 45 kHz - 48 kHz = 3 kHz (bin 512); nothing should be there.
        val frames = fftSize * 3
        val buffer = SignalGenerator.sine(1536.0 * rate / fftSize, frames, rate, channels = 1, amplitude = 0.5f)
        val p = HarmonicProcessor(drive(2 to 0.0, 3 to 0.0, 4 to 0.0, 5 to 0.0))
        p.prepare(TestSupport.format(frames, 1))
        p.process(buffer)
        val slice = FloatArray(fftSize)
        buffer.data[0].copyInto(slice, 0, fftSize * 2, fftSize * 3)
        val folded = 20 * log10(Fft(fftSize).magnitude(slice)[512] * 2 + 1e-12)
        assertTrue(folded < -90, "folded 5th at $folded dB")
    }

    @Test
    fun `no DC is left behind`() {
        // Two seconds, measured over the last half: the start-up, while the amplitude follower
        // rises from nothing, is a transient, not an offset.
        val frames = rate * 2
        val buffer = SignalGenerator.sine(100.0, frames, rate, channels = 1, amplitude = 1.0f)
        val input = buffer.copy()
        val p = HarmonicProcessor(drive(2 to 0.0, 4 to 0.0))
        p.prepare(TestSupport.format(frames, 1))
        p.process(buffer)
        // What was added, averaged over the last half second.
        val mean = (frames * 3 / 4 until frames).sumOf { (buffer.data[0][it] - input.data[0][it]).toDouble() } / (frames / 4)
        assertTrue(abs(mean) < 1e-4, "DC $mean")
    }

    @Test
    fun `a change of settings glides instead of jumping`() {
        val p = HarmonicProcessor(HarmonicDrive.SILENT)
        val block = 480
        p.prepare(AudioFormat(rate, 1, block))
        var phase = 0.0
        var worst = 0.0
        var previous = 0.0
        repeat(40) { k ->
            if (k == 10) p.update(drive(2 to 0.0, 3 to 0.0))
            val buffer = AudioBuffer(1, block)
            for (i in 0 until block) {
                buffer.data[0][i] = (0.5 * kotlin.math.sin(phase)).toFloat()
                phase += 2 * Math.PI * 100.0 / rate
            }
            p.process(buffer)
            for (i in 0 until block) {
                val v = buffer.data[0][i].toDouble()
                worst = max(worst, abs(v - previous))
                previous = v
            }
        }
        // A 100 Hz sine at 0.5 moves at most 0.0066 per sample; with its harmonics fading in over
        // 30 ms the step stays of that order. An instant switch would jump by tenths.
        assertTrue(worst < 0.03, "largest sample step $worst")
    }

    @Test
    fun `a transient bigger than the follower expects stays bounded`() {
        val frames = 4800
        val buffer = AudioBuffer(1, frames)
        for (i in 0 until frames) buffer.data[0][i] = if (i < 4000) 0.001f else 0.9f
        val p = HarmonicProcessor(drive(*(2..16).map { it to 0.0 }.toTypedArray()))
        p.prepare(TestSupport.format(frames, 1))
        p.process(buffer)
        val peak = buffer.data[0].maxOf { abs(it) }
        assertTrue(peak < 20f, "peak $peak")
    }
}

class NosRolloffTest {

    @Test
    fun `the roll-off hits its two points at every common rate`() {
        for (rate in listOf(44100, 48000, 88200, 96000, 192000)) {
            assertEquals(-1.0, NosRolloff.responseDb(12000.0, rate), 0.1, "$rate at 12 kHz")
            assertEquals(-3.2, NosRolloff.responseDb(20000.0, rate), 0.1, "$rate at 20 kHz")
            assertTrue(NosRolloff.responseDb(1000.0, rate) > -0.05, "$rate leaves 1 kHz alone")
            assertTrue(NosRolloff.responseDb(6000.0, rate) > -0.3, "$rate barely touches 6 kHz")
        }
    }

    @Test
    fun `switched off it is an exact bypass, and switched on it filters`() {
        val nos = NosRolloff()
        nos.prepare(TestSupport.format(4800, 1))
        val tone = SignalGenerator.sine(18000.0, 4800, TestSupport.SAMPLE_RATE, channels = 1, amplitude = 0.5f)
        val untouched = tone.copy()
        nos.process(tone)
        assertTrue(tone.data[0].contentEquals(untouched.data[0]))
        assertTrue(nos.idle)
        nos.setEnabled(true)
        repeat(4) { nos.process(SignalGenerator.sine(18000.0, 4800, TestSupport.SAMPLE_RATE, channels = 1, amplitude = 0.5f)) }
        val after = SignalGenerator.sine(18000.0, 4800, TestSupport.SAMPLE_RATE, channels = 1, amplitude = 0.5f)
        nos.process(after)
        val peak = (2400 until 4800).maxOf { abs(after.data[0][it]) }
        assertTrue(peak < 0.45f, "18 kHz peak $peak")
    }
}

class PeakLimiterTest {

    @Test
    fun `music under the ceiling passes bit for bit`() {
        val limiter = PeakLimiter()
        limiter.prepare(TestSupport.format(4800, 2))
        val tone = SignalGenerator.sine(1000.0, 4800, TestSupport.SAMPLE_RATE, channels = 2, amplitude = 0.9f)
        val before = tone.copy()
        limiter.process(tone)
        assertTrue(tone.data[0].contentEquals(before.data[0]) && tone.data[1].contentEquals(before.data[1]))
    }

    @Test
    fun `overs are held under the ceiling`() {
        val limiter = PeakLimiter()
        limiter.prepare(TestSupport.format(4800, 1))
        val loud = SignalGenerator.sine(1000.0, 4800, TestSupport.SAMPLE_RATE, channels = 1, amplitude = 1.6f)
        limiter.process(loud)
        val ceiling = 10.0.pow(PeakLimiter.CEILING_DB / 20)
        assertTrue(loud.data[0].all { abs(it) <= ceiling + 1e-6 }, "peak ${loud.data[0].maxOf { abs(it) }}")
    }
}
