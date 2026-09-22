package mmm.usb

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsoPacketSchedulerTest {

    @Test
    fun `one second of packets carries exactly the sample rate`() {
        for (rate in listOf(44100, 48000, 88200, 96000)) {
            for (pps in listOf(1000, 8000)) {
                val scheduler = IsoPacketScheduler(rate, pps, maxFramesPerPacket = rate / pps + 2)
                val total = (0 until pps).sumOf { scheduler.next() }
                assertEquals(rate, total, "$rate Hz at $pps packets/s")
            }
        }
    }

    @Test
    fun `44_1 kHz is nine packets of 44 and one of 45`() {
        val scheduler = IsoPacketScheduler(44100, 1000, maxFramesPerPacket = 46)
        val first = List(10) { scheduler.next() }
        assertEquals(9, first.count { it == 44 })
        assertEquals(1, first.count { it == 45 })
    }

    @Test
    fun `48 kHz on full speed is always 48`() {
        val scheduler = IsoPacketScheduler(48000, 1000, maxFramesPerPacket = 49)
        repeat(5000) { assertEquals(48, scheduler.next()) }
    }

    @Test
    fun `corrections move one packet by one frame and are clamped to the endpoint`() {
        val scheduler = IsoPacketScheduler(48000, 1000, maxFramesPerPacket = 49)
        assertEquals(49, scheduler.next(+1))
        assertEquals(1, scheduler.appliedCorrection)
        assertEquals(47, scheduler.next(-1))
        assertEquals(-1, scheduler.appliedCorrection)

        val tight = IsoPacketScheduler(48000, 1000, maxFramesPerPacket = 48)
        assertEquals(48, tight.next(+1))
        assertEquals(0, tight.appliedCorrection)
    }

    @Test
    fun `a rate the endpoint cannot carry is refused up front`() {
        assertFailsWith<IllegalArgumentException> {
            IsoPacketScheduler(96000, 1000, maxFramesPerPacket = 48)
        }
    }

    @Test
    fun `packet rate follows bus speed and interval`() {
        assertEquals(1000, IsoPacketScheduler.packetsPerSecond(highSpeed = false, bInterval = 1))
        assertEquals(8000, IsoPacketScheduler.packetsPerSecond(highSpeed = true, bInterval = 1))
        assertEquals(1000, IsoPacketScheduler.packetsPerSecond(highSpeed = true, bInterval = 4))
    }
}

class DriftServoTest {

    /**
     * A producer on its own clock writes 512-frame blocks; the bus drains one packet a millisecond.
     * Returns the servo, the lowest and highest backlog seen after settling, and underrun frames.
     */
    private data class Run(
        val servo: DriftServo,
        val low: Int,
        val high: Int,
        val underrun: Long,
        /** Net corrections (faster minus slower) after the settling minute, per second. */
        val netPerSecond: Double,
        /** All corrections after the settling minute. */
        val settledCorrections: Long,
    )

    private fun simulate(ppm: Double, seconds: Int, servoOn: Boolean = true): Run {
        val rate = 48000
        val pps = 1000
        val target = 2048
        val scheduler = IsoPacketScheduler(rate, pps, maxFramesPerPacket = 49)
        val servo = DriftServo(target, deadbandFrames = 96, packetsPerSecond = pps)

        val producedPerTick = rate * (1 + ppm / 1e6) / pps
        var credit = 0.0
        var buffered = target
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var underrun = 0L
        // The buffer starts at the target but the block sawtooth averages below it, so the first
        // few seconds are the servo settling onto the true mean. Rates are counted after that.
        val settleTicks = 60 * pps
        var fasterAtSettle = 0L
        var slowerAtSettle = 0L

        for (tick in 0 until seconds * pps) {
            credit += producedPerTick
            while (credit >= 512) {
                buffered += 512
                credit -= 512
            }
            val correction = if (servoOn) servo.update(buffered) else 0
            val take = scheduler.next(correction)
            if (take > buffered) {
                underrun += take - buffered
                buffered = 0
            } else {
                buffered -= take
            }
            if (tick == settleTicks) {
                fasterAtSettle = servo.fasterCount
                slowerAtSettle = servo.slowerCount
            }
            if (tick > 10 * pps) {
                low = minOf(low, buffered)
                high = maxOf(high, buffered)
            }
        }
        val faster = servo.fasterCount - fasterAtSettle
        val slower = servo.slowerCount - slowerAtSettle
        val measured = (seconds - settleTicks / pps).coerceAtLeast(1)
        return Run(servo, low, high, underrun, (faster - slower).toDouble() / measured, faster + slower)
    }

    @Test
    fun `without correction a 100 ppm mismatch runs the buffer dry`() {
        // The control case: this is what happens to the live mode without the servo.
        val run = simulate(ppm = -100.0, seconds = 600, servoOn = false)
        assertTrue(run.underrun > 0, "expected an underrun without the servo")
    }

    @Test
    fun `a slow producer is held steady for an hour`() {
        val run = simulate(ppm = -100.0, seconds = 3600)
        assertEquals(0L, run.underrun)
        assertTrue(run.low > 0, "backlog touched zero: ${run.low}")
        assertTrue(run.high < 2048 + 1024, "backlog crept up to ${run.high}")
    }

    @Test
    fun `a fast producer is held steady for an hour`() {
        val run = simulate(ppm = +100.0, seconds = 3600)
        assertEquals(0L, run.underrun)
        assertTrue(run.high < 2048 + 1024, "backlog crept up to ${run.high}")
        assertTrue(run.low > 0, "backlog touched zero: ${run.low}")
    }

    @Test
    fun `net corrections match the clock mismatch`() {
        // 100 ppm of 48 kHz is 4.8 frames a second; the servo should end up doing about that.
        for (ppm in listOf(-100.0, -30.0, 30.0, 100.0)) {
            val netPerSecond = simulate(ppm, seconds = 1800).netPerSecond
            val expected = 48000 * ppm / 1e6
            assertTrue(
                abs(netPerSecond - expected) < 0.05 * abs(expected) + 0.02,
                "$ppm ppm: expected $expected corrections/s, got $netPerSecond",
            )
        }
    }

    @Test
    fun `matched clocks need almost no correction once settled`() {
        val run = simulate(ppm = 0.0, seconds = 600)
        assertTrue(run.settledCorrections < 10, "servo hunted with matched clocks: ${run.settledCorrections}")
    }
}

class PcmPackerTest {

    private fun stereo(bytes: Int, bits: Int) = UsbPcmFormat(channels = 2, bytesPerSample = bytes, bitResolution = bits)

    private fun ByteBuffer.le(offset: Int, size: Int): Long {
        var value = 0L
        for (i in size - 1 downTo 0) value = (value shl 8) or (get(offset + i).toLong() and 0xFF)
        // Sign-extend from the subslot width.
        val shift = 64 - size * 8
        return (value shl shift) shr shift
    }

    @Test
    fun `16 bit full scale, negative full scale and clipping`() {
        val packer = PcmPacker(stereo(2, 16), dither = false)
        val out = ByteBuffer.allocate(16)
        packer.pack(floatArrayOf(1.0f, -1.0f, 2.0f, 0.5f), frames = 2, out, 0)
        assertEquals(32767, out.le(0, 2))
        assertEquals(-32768, out.le(2, 2))
        assertEquals(32767, out.le(4, 2))
        assertEquals(16384, out.le(6, 2))
    }

    @Test
    fun `samples go out little endian`() {
        val packer = PcmPacker(stereo(2, 16), dither = false)
        val out = ByteBuffer.allocate(4)
        packer.pack(floatArrayOf(0.5f, 0f), frames = 1, out, 0)
        assertEquals(0x00.toByte(), out.get(0))
        assertEquals(0x40.toByte(), out.get(1))
    }

    @Test
    fun `24 bit in 3 bytes and left justified in 4`() {
        val three = ByteBuffer.allocate(6)
        PcmPacker(stereo(3, 24)).pack(floatArrayOf(0.5f, -0.5f), 1, three, 0)
        assertEquals(0x400000, three.le(0, 3))
        assertEquals(-0x400000, three.le(3, 3))

        val four = ByteBuffer.allocate(8)
        PcmPacker(stereo(4, 24)).pack(floatArrayOf(0.5f, 0f), 1, four, 0)
        assertEquals(0x40000000L, four.le(0, 4))
        assertEquals(0.toByte(), four.get(0), "low byte of a 24-in-32 sample is padding")
    }

    @Test
    fun `writes at the given offset and reports its length`() {
        val out = ByteBuffer.allocate(12)
        val written = PcmPacker(stereo(2, 16), dither = false).pack(floatArrayOf(1f, 1f), 1, out, 8)
        assertEquals(4, written)
        assertEquals(0, out.get(0))
        assertEquals(32767, out.le(8, 2))
    }

    @Test
    fun `16 bit dither stays within one LSB and averages to zero`() {
        val packer = PcmPacker(stereo(2, 16))
        val frames = 20000
        val out = ByteBuffer.allocate(frames * 4)
        packer.pack(FloatArray(frames * 2), frames, out, 0)
        var sum = 0L
        var nonZero = 0
        for (i in 0 until frames * 2) {
            val code = out.le(i * 2, 2)
            assertTrue(abs(code) <= 1, "dither produced $code")
            sum += code
            if (code != 0L) nonZero++
        }
        assertTrue(abs(sum.toDouble() / (frames * 2)) < 0.02, "dither is biased")
        assertTrue(nonZero > frames / 4, "dither is missing")
    }

    @Test
    fun `silence is zero bytes`() {
        val out = ByteBuffer.allocate(8)
        for (i in 0 until 8) out.put(i, 0x55)
        assertEquals(8, PcmPacker(stereo(2, 16)).silence(2, out, 0))
        for (i in 0 until 8) assertEquals(0, out.get(i))
    }
}
