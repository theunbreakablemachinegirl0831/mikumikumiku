package mmm.source.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mmm.usb.UsbAudioAlternate

/** What the diagnostics screen shows about the USB path. */
public data class UsbAudioReport(
    val supported: Boolean = true,
    val candidates: List<UsbAudioCandidate> = emptyList(),
    val selected: UsbAudioCandidate? = null,
    val permissionGranted: Boolean = false,
    val claim: ClaimResult? = null,
    val chosenAlternate: UsbAudioAlternate? = null,
    val androidOutputsBefore: List<String> = emptyList(),
    val androidOutputsAfter: List<String> = emptyList(),
    val message: String? = null,
) {
    public val holdingExclusively: Boolean get() = claim?.exclusive == true
}

/**
 * Drives discovery, permission and the exclusive claim for USB DACs.
 *
 * Deliberately stops short of playing anything. Whether the platform will hand the device over is
 * the part that decides the whole approach, and it can be answered without a line of native code;
 * the isochronous streamer is only worth writing once the answer is yes.
 */
public class UsbAudioController(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val discovery = UsbAudioDiscovery(context)
    private val claimer = UsbExclusiveClaim(context, discovery)

    private val _report = MutableStateFlow(UsbAudioReport())
    public val report: StateFlow<UsbAudioReport> = _report.asStateFlow()

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    _report.value = _report.value.copy(
                        permissionGranted = granted,
                        message = if (granted) "USB 접근이 허용되었다" else "USB 접근이 거부되었다",
                    )
                    refresh()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // The device is gone; anything we were holding is already invalid.
                    claimer.release()
                    _report.value = _report.value.copy(
                        selected = null,
                        claim = null,
                        chosenAlternate = null,
                        message = "USB 장치가 분리되었다",
                    )
                    refresh()
                }
            }
        }
    }

    public fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        refresh()
    }

    public fun stop() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        claimer.release()
    }

    /** Re-reads what is attached and what we can see of it. */
    public fun refresh() {
        val attached = discovery.attachedAudioDevices()
        if (attached.isEmpty()) {
            _report.value = _report.value.copy(
                candidates = emptyList(),
                selected = null,
                permissionGranted = false,
                androidOutputsBefore = discovery.androidOutputs(),
                message = "연결된 USB 오디오 장치가 없다",
            )
            return
        }

        val granted = attached.all(discovery::hasPermission)
        val candidates = discovery.inspectAll()
        _report.value = _report.value.copy(
            candidates = candidates,
            selected = _report.value.selected?.let { previous ->
                candidates.firstOrNull { it.deviceName == previous.deviceName }
            } ?: candidates.firstOrNull(),
            permissionGranted = granted,
            androidOutputsBefore = discovery.androidOutputs(),
            message = when {
                !granted -> "USB 접근 권한이 필요하다"
                candidates.isEmpty() -> "오디오 스트리밍 인터페이스를 찾지 못했다"
                else -> null
            },
        )
    }

    /** Asks the user for access to the first attached audio device. */
    public fun requestPermission(device: UsbDevice? = null) {
        val target = device ?: discovery.attachedAudioDevices().firstOrNull() ?: return
        if (usbManager.hasPermission(target)) {
            refresh()
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(target, intent)
    }

    public fun select(candidate: UsbAudioCandidate) {
        _report.value = _report.value.copy(selected = candidate, claim = null, chosenAlternate = null)
    }

    /**
     * The experiment: claim the DAC and see whether Android gives it up.
     *
     * @param sampleRate the rate we would want to play at, used only to pick an alternate setting
     */
    public fun claimExclusively(sampleRate: Int = 48000): ClaimResult? {
        val candidate = _report.value.selected ?: return null
        val before = discovery.androidOutputs()

        val result = claimer.claim(candidate.device, candidate.function)
        val alternate = candidate.function.bestMatch(sampleRate)

        if (result.claimed && alternate != null) {
            claimer.selectAlternate(alternate)
            if (alternate.spec == mmm.usb.UsbAudioSpec.UAC1) {
                claimer.setUac1SampleRate(alternate, sampleRate)
            }
        }

        _report.value = _report.value.copy(
            claim = result,
            chosenAlternate = alternate,
            androidOutputsBefore = before,
            androidOutputsAfter = discovery.androidOutputs(),
            message = result.detail,
        )
        return result
    }

    public fun releaseClaim() {
        claimer.release()
        _report.value = _report.value.copy(
            claim = null,
            chosenAlternate = null,
            androidOutputsAfter = discovery.androidOutputs(),
            message = "점유를 해제했다",
        )
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "mmm.source.usb.USB_PERMISSION"
    }
}
