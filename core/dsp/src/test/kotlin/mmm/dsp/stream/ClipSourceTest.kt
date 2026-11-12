package mmm.dsp.stream

import mmm.dsp.AudioBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipSourceTest {

    private fun clip(frames: Int): AudioBuffer =
        AudioBuffer(1, frames).apply {
            this.frames = frames
            for (i in 0 until frames) data[0][i] = i.toFloat()
        }

    @Test
    fun `a non-looping clip reports exhaustion once`() {
        val source = ClipSource(clip(10), 48000, loop = false)
        val out = AudioBuffer(1, 8)
        assertEquals(8, source.read(out))
        assertEquals(2, source.read(out))
        assertEquals(-1, source.read(out))
    }

    @Test
    fun `a looping clip wraps back to the start`() {
        val source = ClipSource(clip(10), 48000, loop = true)
        val out = AudioBuffer(1, 8)
        source.read(out)
        assertEquals(2, source.read(out))
        assertEquals(8f, out.data[0][0])

        assertEquals(8, source.read(out))
        assertEquals(0f, out.data[0][0], "the loop should resume from the first frame")
    }

    @Test
    fun `seeking is clamped to the clip`() {
        val source = ClipSource(clip(10), 48000, loop = false)
        source.seek(50)
        assertEquals(-1, source.read(AudioBuffer(1, 4)))

        source.seek(-5)
        val out = AudioBuffer(1, 4)
        assertEquals(4, source.read(out))
        assertEquals(0f, out.data[0][0])
    }
}
