package mmm.source.capture

import mmm.audio.OutputRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureDiagnosticsTest {

    private fun running() = CaptureState.Running(
        sampleRate = 48000,
        channels = 2,
        route = OutputRoute.MEDIA,
        latencyMs = 60,
        outputDevice = null,
    )

    @Test
    fun `a silent stream is only called blocked after long enough to rule out a pause`() {
        val silent = CaptureTelemetry(sawSignal = false, framesProcessed = 48000)

        val early = CaptureDiagnostics.evaluate(running(), silent, runningMs = 1000)
        assertEquals(CaptureVerdict.Status.WAITING, early.status)

        val late = CaptureDiagnostics.evaluate(running(), silent, runningMs = 8000)
        assertEquals(CaptureVerdict.Status.SILENT, late.status)
        assertTrue(late.suggestions.isNotEmpty(), "a blocked verdict has to say what to try next")
    }

    @Test
    fun `the blocked verdict names the source app when we know it`() {
        val verdict = CaptureDiagnostics.evaluate(
            running(),
            CaptureTelemetry(sawSignal = false, framesProcessed = 48000),
            runningMs = 8000,
            sourceAppLabel = "Spotify",
        )
        assertTrue("Spotify" in verdict.detail)
    }

    @Test
    fun `signal plus clean buffers is healthy`() {
        val verdict = CaptureDiagnostics.evaluate(
            running(),
            CaptureTelemetry(sawSignal = true, inputRmsDb = -18.0, outputRmsDb = -18.4),
            runningMs = 8000,
        )
        assertEquals(CaptureVerdict.Status.HEALTHY, verdict.status)
    }

    @Test
    fun `heavy under-runs downgrade an otherwise working capture`() {
        val verdict = CaptureDiagnostics.evaluate(
            running(),
            CaptureTelemetry(sawSignal = true, underrunFrames = 96_000),
            runningMs = 8000,
        )
        assertEquals(CaptureVerdict.Status.DEGRADED, verdict.status)
    }

    @Test
    fun `an output error outranks everything else`() {
        val verdict = CaptureDiagnostics.evaluate(
            running(),
            CaptureTelemetry(sawSignal = true, error = "AudioTrack write failed (-3)"),
            runningMs = 8000,
        )
        assertEquals(CaptureVerdict.Status.FAILED, verdict.status)
    }

    @Test
    fun `muting only counts as a success when capture was working first`() {
        assertTrue(VolumeSeparation.captureSurvivesMuting(true, true))
        assertTrue(!VolumeSeparation.captureSurvivesMuting(true, false))
        // Never captured anything, so muting proves nothing.
        assertTrue(!VolumeSeparation.captureSurvivesMuting(false, false))
    }
}
