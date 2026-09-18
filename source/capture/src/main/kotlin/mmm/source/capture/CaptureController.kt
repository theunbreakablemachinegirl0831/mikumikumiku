package mmm.source.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mmm.audio.OutputRoute
import mmm.dsp.AudioProcessor

/**
 * Binds the UI to [SystemAudioCaptureService].
 *
 * Exists so screens never touch the service directly: the capture grant is a one-shot Activity
 * result that has to be handed to a foreground service in a specific order, and getting that order
 * wrong throws on Android 14. Keeping it in one place means only one place can get it wrong.
 */
public class CaptureController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    public val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    public val telemetry: StateFlow<CaptureTelemetry> = _telemetry.asStateFlow()

    private var service: SystemAudioCaptureService? = null
    private var bound = false

    /** Milliseconds capture has been up, for the silence verdict. */
    public val runningMs: Long get() = service?.runningMs ?: 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? SystemAudioCaptureService.LocalBinder)?.service ?: return
            service = local
            bound = true
            scope.launch { local.state.collect { _state.value = it } }
            scope.launch { local.telemetry.collect { _telemetry.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _state.value = CaptureState.Idle
        }
    }

    /** The Intent to launch with an Activity result contract to ask for the capture grant. */
    public fun permissionIntent(): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    /** Call with the Activity result from [permissionIntent]. */
    public fun start(resultCode: Int, resultData: Intent, route: OutputRoute) {
        val intent = SystemAudioCaptureService.startIntent(context, resultCode, resultData, route)
        context.startForegroundService(intent)
        if (!bound) {
            context.bindService(
                Intent(context, SystemAudioCaptureService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    public fun stop() {
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
            service = null
        }
        context.startService(SystemAudioCaptureService.stopIntent(context))
        _state.value = CaptureState.Idle
    }

    public fun setArtifact(processor: AudioProcessor, levelMatched: Boolean = true) {
        service?.setArtifact(processor, levelMatched)
    }

    public fun clearArtifact() {
        service?.clearArtifact()
    }
}
