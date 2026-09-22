package mmm.dsp.stream

import mmm.dsp.AudioBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingBufferSourceTest {

    private fun ramp(start: Int, count: Int, channels: Int = 2): AudioBuffer =
        AudioBuffer(channels, count).apply {
            frames = count
            for (ch in 0 until channels) {
                for (i in 0 until count) data[ch][i] = (start + i).toFloat() + ch * 10_000f
            }
        }

    @Test
    fun `audio comes out in the order it went in, across the wrap point`() {
        val ring = RingBufferSource(48000, 2, capacityFrames = 100)
        val out = AudioBuffer(2, 30)

        var expected = 0
        repeat(20) { round ->
            ring.write(ramp(round * 25, 25))
            while (ring.availableFrames >= 30) {
                ring.read(out)
                for (i in 0 until out.frames) {
                    assertEquals(expected.toFloat(), out.data[0][i], "frame $expected out of order")
                    assertEquals(expected + 10_000f, out.data[1][i], "channel 1 diverged at $expected")
                    expected++
                }
            }
        }
        assertTrue(expected > 400, "only $expected frames made it through")
    }

    @Test
    fun `a partial read returns what is there and reports the shortfall`() {
        val ring = RingBufferSource(48000, 1, capacityFrames = 100)
        ring.write(ramp(0, 10, channels = 1))

        val out = AudioBuffer(1, 32)
        assertEquals(10, ring.read(out))
        assertEquals(10, out.frames)
        assertEquals(22, ring.underrunFrames.toInt(), "the shortfall has to be visible to the UI")
    }

    @Test
    fun `an empty buffer reads zero rather than blocking or failing`() {
        val ring = RingBufferSource(48000, 2, capacityFrames = 100)
        val out = AudioBuffer(2, 16)
        assertEquals(0, ring.read(out))
        assertEquals(0, out.frames)
    }

    @Test
    fun `a reader that falls behind drops the oldest audio and says so`() {
        val ring = RingBufferSource(48000, 1, capacityFrames = 64)
        ring.write(ramp(0, 50, channels = 1))
        ring.write(ramp(50, 50, channels = 1))

        assertEquals(36, ring.overrunFrames.toInt(), "100 frames into a 64-frame buffer drops 36")
        assertEquals(64, ring.availableFrames)

        // What survives is the newest 64 frames, i.e. 36..99.
        val out = AudioBuffer(1, 64)
        ring.read(out)
        assertEquals(36f, out.data[0][0])
        assertEquals(99f, out.data[0][63])
    }

    @Test
    fun `clearing drops pending audio without disturbing the counters`() {
        val ring = RingBufferSource(48000, 1, capacityFrames = 64)
        ring.write(ramp(0, 40, channels = 1))
        ring.clear()
        assertEquals(0, ring.availableFrames)

        ring.write(ramp(100, 10, channels = 1))
        val out = AudioBuffer(1, 10)
        assertEquals(10, ring.read(out))
        assertEquals(100f, out.data[0][0])
    }

    @Test
    fun `writing exactly the capacity loses nothing`() {
        val ring = RingBufferSource(48000, 1, capacityFrames = 64)
        ring.write(ramp(0, 64, channels = 1))
        assertEquals(0, ring.overrunFrames.toInt())
        assertEquals(64, ring.availableFrames)
    }
}
