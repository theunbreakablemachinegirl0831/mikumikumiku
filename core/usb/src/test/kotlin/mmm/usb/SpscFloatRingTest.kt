package mmm.usb

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class SpscFloatRingTest {

    @Test
    fun `write and read wrap around the end in order`() {
        val ring = SpscFloatRing(capacityFrames = 5, channels = 2)
        val out = FloatArray(10)
        var next = 0f
        var expected = 0f
        repeat(20) {
            val chunk = FloatArray(6) { next++ }
            assertEquals(3, ring.write(chunk, 3))
            assertEquals(3, ring.read(out, 3))
            for (i in 0 until 6) assertEquals(expected++, out[i])
        }
    }

    @Test
    fun `a full ring takes nothing and an empty one gives nothing`() {
        val ring = SpscFloatRing(capacityFrames = 4, channels = 1)
        assertEquals(4, ring.write(FloatArray(6) { it.toFloat() }, 6))
        assertEquals(0, ring.write(floatArrayOf(9f), 1))
        val out = FloatArray(8)
        assertEquals(4, ring.read(out, 8))
        assertEquals(listOf(0f, 1f, 2f, 3f), out.take(4))
        assertEquals(0, ring.read(out, 1))
    }

    @Test
    fun `writes from an offset`() {
        val ring = SpscFloatRing(capacityFrames = 4, channels = 2)
        ring.write(floatArrayOf(0f, 0f, 7f, 8f), frames = 1, offsetFrames = 1)
        val out = FloatArray(2)
        ring.read(out, 1)
        assertEquals(listOf(7f, 8f), out.toList())
    }

    @Test
    fun `a producer and consumer thread see every sample once and in order`() {
        val ring = SpscFloatRing(capacityFrames = 64, channels = 2)
        val total = 200_000
        val producer = thread {
            val block = FloatArray(2 * 37)
            var sent = 0
            while (sent < total) {
                val frames = minOf(37, total - sent)
                for (f in 0 until frames) {
                    block[2 * f] = (sent + f).toFloat()
                    block[2 * f + 1] = -(sent + f).toFloat()
                }
                var done = 0
                while (done < frames) done += ring.write(block, frames - done, done)
                sent += frames
            }
        }
        val out = FloatArray(2 * 29)
        var received = 0
        while (received < total) {
            val got = ring.read(out, 29)
            for (f in 0 until got) {
                assertEquals((received + f).toFloat(), out[2 * f])
                assertEquals(-(received + f).toFloat(), out[2 * f + 1])
            }
            received += got
        }
        producer.join()
        assertEquals(0, ring.availableFrames)
    }
}
