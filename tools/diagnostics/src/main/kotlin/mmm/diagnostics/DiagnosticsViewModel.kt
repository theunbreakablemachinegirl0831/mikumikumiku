package mmm.diagnostics

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mmm.audio.OutputRoute
import mmm.dsp.BandGrid
import mmm.dsp.BandResolution
import mmm.dsp.artifacts.BandBoostProcessor
import mmm.source.capture.CaptureController
import mmm.source.capture.CaptureDiagnostics
import mmm.source.capture.CaptureState
import mmm.source.capture.CaptureTelemetry
import mmm.source.capture.CaptureVerdict
import mmm.source.capture.PlayingAppsMonitor
import mmm.source.capture.PlayingStream
import mmm.source.capture.VolumeSeparation

/** Everything the one diagnostics screen shows. */
public data class DiagnosticsUiState(
    val capture: CaptureState = CaptureState.Idle,
    val telemetry: CaptureTelemetry = CaptureTelemetry(),
    val verdict: CaptureVerdict = CaptureVerdict(
        CaptureVerdict.Status.UNKNOWN,
        "대기 중",
        "캡처를 시작하면 신호가 들어오는지 확인한다.",
    ),
    val playing: List<PlayingStream> = emptyList(),
    val route: OutputRoute = OutputRoute.MEDIA,
    val mediaMuted: Boolean = false,
    val artifactOn: Boolean = false,
    val runningMs: Long = 0,
    /**
     * Result of the one experiment this build exists for: did capture keep working once the media
     * stream was muted? Null until both halves have been observed.
     */
    val mutingVerdict: Boolean? = null,
)

/**
 * Drives the capture stack and records the muting experiment.
 *
 * The experiment is stateful rather than a single reading: it needs to see capture working with
 * the media stream up, then still working with it at zero. One observation on its own cannot tell
 * "the strategy works" from "nothing was playing in the first place".
 */
public class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = CaptureController(application, viewModelScope)
    private val playingMonitor = PlayingAppsMonitor(application)
    private val volume = VolumeSeparation(application)

    private val _ui = MutableStateFlow(DiagnosticsUiState())
    public val ui: StateFlow<DiagnosticsUiState> = _ui.asStateFlow()

    private var sawSignalBeforeMuting = false
    private var sawSignalAfterMuting = false

    init {
        playingMonitor.start()
        viewModelScope.launch { controller.state.collect { onState(it) } }
        viewModelScope.launch { controller.telemetry.collect { onTelemetry(it) } }
        viewModelScope.launch { playingMonitor.streams.collect { streams -> _ui.value = _ui.value.copy(playing = streams) } }
        viewModelScope.launch {
            // The silence verdict is time-based, so the screen has to keep ticking even when no
            // new telemetry arrives - otherwise a blocked app never gets past "waiting".
            while (isActive) {
                kotlinx.coroutines.delay(250)
                refreshVerdict()
            }
        }
    }

    public fun permissionIntent(): Intent = controller.permissionIntent()

    public fun onPermissionResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        sawSignalBeforeMuting = false
        sawSignalAfterMuting = false
        _ui.value = _ui.value.copy(mutingVerdict = null)
        controller.start(resultCode, data, _ui.value.route)
    }

    public fun stop() {
        controller.stop()
        if (_ui.value.mediaMuted) setMediaMuted(false)
    }

    public fun selectRoute(route: OutputRoute) {
        if (!route.implemented) return
        _ui.value = _ui.value.copy(route = route)
    }

    /** Strategy A under test: mute the source's stream and see whether capture survives it. */
    public fun setMediaMuted(muted: Boolean) {
        if (muted) volume.engage() else volume.restore()
        // Each time muting is switched on the "after" half starts over, so a result from an
        // earlier attempt cannot be mistaken for this one.
        if (muted) sawSignalAfterMuting = false
        _ui.value = _ui.value.copy(mediaMuted = muted, mutingVerdict = null)
    }

    /** A deliberately obvious artifact, to confirm processing is reaching the ears at all. */
    public fun setArtifact(on: Boolean) {
        if (on) {
            val band = BandGrid(BandResolution.OCTAVE).nearest(1000.0)
            controller.setArtifact(BandBoostProcessor(band, gainDb = 12.0), levelMatched = false)
        } else {
            controller.clearArtifact()
        }
        _ui.value = _ui.value.copy(artifactOn = on)
    }

    private fun onState(state: CaptureState) {
        _ui.value = _ui.value.copy(capture = state)
        refreshVerdict()
    }

    private fun onTelemetry(telemetry: CaptureTelemetry) {
        val muted = _ui.value.mediaMuted
        if (telemetry.sawSignal) {
            if (muted) sawSignalAfterMuting = true else sawSignalBeforeMuting = true
        }
        // Only meaningful once both halves have been observed: capture working with the media
        // stream up, and then the state of it with the stream at zero.
        val verdict = if (sawSignalBeforeMuting && muted) {
            VolumeSeparation.captureSurvivesMuting(sawSignalBeforeMuting, sawSignalAfterMuting)
        } else {
            _ui.value.mutingVerdict
        }
        _ui.value = _ui.value.copy(telemetry = telemetry, mutingVerdict = verdict)
        refreshVerdict()
    }

    private fun refreshVerdict() {
        val current = _ui.value
        _ui.value = current.copy(
            runningMs = controller.runningMs,
            verdict = CaptureDiagnostics.evaluate(
                state = current.capture,
                telemetry = current.telemetry,
                runningMs = controller.runningMs,
                sourceAppLabel = null,
            ),
        )
    }

    override fun onCleared() {
        playingMonitor.stop()
        controller.stop()
        volume.restore()
        super.onCleared()
    }
}
