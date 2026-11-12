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

/**
 * Where the listener actually hears sound after a claim.
 *
 * The platform's own report turned out not to be trustworthy at the one moment it matters, so the
 * ear is the instrument of record. [SPEAKER] is the success case: it means Android lost the DAC
 * and fell back, leaving the device for us.
 */
public enum class AcousticResult(public val label: String, public val meaning: String) {
    SPEAKER(
        "내장 스피커에서 난다",
        "성공이다. Android가 DAC을 놓고 폴백했다는 뜻이고, 이제 DAC은 우리 차지다.",
    ),
    DAC(
        "여전히 DAC에서 난다",
        "Android가 아직 DAC을 쓰고 있다. 개발자 옵션의 'USB 오디오 라우팅 비활성화'를 켜고 다시 시도한다.",
    ),
    SILENT(
        "아무 데서도 안 난다",
        "장치는 놓았는데 폴백할 출력이 없는 상태다. 스피커 음량과 방해 금지 모드를 확인한다.",
    ),
}

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
    /** What the listener reported hearing, which outranks the platform's own routing report. */
    val acoustic: AcousticResult? = null,
) {
    /**
     * Exclusive control, decided by the ear when we have that and by the platform otherwise.
     * The routing probe has been wrong here before; a listener saying the sound moved has not.
     */
    public val holdingExclusively: Boolean
        get() = when (acoustic) {
            AcousticResult.SPEAKER -> claim?.claimed == true
            AcousticResult.DAC, AcousticResult.SILENT -> false
            null -> claim?.exclusive == true
        }
}

/**
 * Drives discovery, permission and the exclusive claim for USB DACs, and opens the stream on a
 * claimed one with [openStream].
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
        claimedSampleRate = sampleRate

        if (result.claimed && alternate != null) {
            claimer.selectAlternate(alternate)
            if (alternate.spec == mmm.usb.UsbAudioSpec.UAC1) {
                claimer.setUac1SampleRate(alternate, sampleRate)
            }
        }

        _report.value = _report.value.copy(
            claim = result,
            chosenAlternate = alternate,
            acoustic = null,
            androidOutputsBefore = before,
            androidOutputsAfter = discovery.androidOutputs(),
            message = result.detail,
        )
        return result
    }

    /** The rate the last [claimExclusively] set the DAC to. */
    public var claimedSampleRate: Int = 0
        private set

    /**
     * A streamer on the claimed DAC, at [claimedSampleRate].
     * Null unless a claim is held and an alternate setting was chosen.
     */
    public fun openStream(clockedProducer: Boolean): UsbIsoStreamer? {
        val current = _report.value
        val alternate = current.chosenAlternate ?: return null
        if (current.claim?.claimed != true) return null
        return claimer.openStreamer(alternate, claimedSampleRate, clockedProducer)
    }

    /** Records what the listener actually heard after claiming. */
    public fun recordAcoustic(result: AcousticResult) {
        _report.value = _report.value.copy(acoustic = result, message = result.meaning)
    }

    public fun releaseClaim() {
        claimer.release()
        _report.value = _report.value.copy(
            claim = null,
            chosenAlternate = null,
            acoustic = null,
            androidOutputsAfter = discovery.androidOutputs(),
            message = "점유를 해제했다",
        )
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "mmm.source.usb.USB_PERMISSION"
    }
}
