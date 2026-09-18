package mmm.source.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import mmm.usb.UsbAudioAlternate
import mmm.usb.UsbAudioFunction

/** Outcome of trying to take a DAC away from the platform. */
public data class ClaimResult(
    val claimed: Boolean,
    /** Whether Android still lists a USB audio output after the attempt. */
    val androidStillHasUsbOutput: Boolean,
    val detail: String,
) {
    /**
     * The claim only counts as exclusive if the platform actually let go. A claim that succeeds
     * while Android keeps its own route means both of us are feeding the DAC, which is the
     * doubling problem all over again.
     */
    public val exclusive: Boolean get() = claimed && !androidStillHasUsbOutput
}

/**
 * Takes exclusive control of a USB DAC's streaming interface.
 *
 * This is the mechanism behind UAPP's separation, and the reason its "audio from other apps"
 * feature only appears with a USB DAC attached: force-claiming the interface detaches the kernel's
 * audio driver, Android's mixer loses the device and falls back to the built-in output, and the
 * only thing still reaching the DAC is us. The source app keeps playing - to the phone's speaker,
 * where it is out of the way - and the listener hears one signal instead of two.
 *
 * Claiming alone is not the whole job: sound needs isochronous transfers, which Android's Java USB
 * API does not expose. What this class establishes is whether the platform will let go at all,
 * which is the question worth answering before writing a native streamer.
 */
public class UsbExclusiveClaim(
    context: Context,
    private val discovery: UsbAudioDiscovery = UsbAudioDiscovery(context),
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces = mutableListOf<Int>()
    private var parkedDevice: UsbDevice? = null
    private var parkOnRelease: Map<Int, Int> = emptyMap()

    public val isHolding: Boolean get() = connection != null

    /** The open connection's file descriptor, which the native streamer will need. */
    public val fileDescriptor: Int get() = connection?.fileDescriptor ?: -1

    /**
     * Claims every streaming interface of [function] on [device], detaching the kernel driver.
     *
     * @param function what [UsbAudioDiscovery] parsed for this device
     */
    public fun claim(device: UsbDevice, function: UsbAudioFunction): ClaimResult {
        if (isHolding) release()
        if (!usbManager.hasPermission(device)) {
            return ClaimResult(false, discovery.androidSeesUsbOutput(), "USB 접근 권한이 없다")
        }

        val opened = usbManager.openDevice(device)
            ?: return ClaimResult(false, discovery.androidSeesUsbOutput(), "장치를 열 수 없다")

        val wanted = buildList {
            function.controlInterfaceNumber?.let(::add)
            addAll(function.streamingInterfaceNumbers)
        }.distinct()

        val failed = mutableListOf<Int>()
        val taken = mutableListOf<Int>()
        for (number in wanted) {
            val iface = (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { it.id == number }
                ?: continue
            // force = true is what issues the disconnect-and-claim that evicts the kernel driver.
            // Without it the claim fails while snd-usb-audio holds the interface.
            if (opened.claimInterface(iface, true)) taken += number else failed += number
        }

        if (taken.isEmpty()) {
            opened.close()
            return ClaimResult(
                claimed = false,
                androidStillHasUsbOutput = discovery.androidSeesUsbOutput(),
                detail = "인터페이스를 하나도 점유하지 못했다 (${wanted.joinToString()})",
            )
        }

        connection = opened
        claimedInterfaces = taken
        parkedDevice = device
        parkOnRelease = function.zeroBandwidthAlternates

        val stillThere = discovery.androidSeesUsbOutput()
        return ClaimResult(
            claimed = true,
            androidStillHasUsbOutput = stillThere,
            detail = buildString {
                append("인터페이스 ").append(taken.joinToString()).append(" 점유")
                if (failed.isNotEmpty()) append(" · 실패: ").append(failed.joinToString())
                append(if (stillThere) " · Android가 아직 USB 출력을 들고 있다" else " · Android가 USB 출력을 놓았다")
            },
        )
    }

    /**
     * Switches a claimed interface into [alternate] so it starts streaming.
     *
     * Isochronous bandwidth is reserved by the alternate setting, so this is also what makes the
     * device's endpoint usable at all - and why [release] puts it back.
     */
    public fun selectAlternate(alternate: UsbAudioAlternate): Boolean {
        val open = connection ?: return false
        return open.controlTransfer(
            REQUEST_TYPE_SET_INTERFACE,
            REQUEST_SET_INTERFACE,
            alternate.alternateSetting,
            alternate.interfaceNumber,
            null,
            0,
            CONTROL_TIMEOUT_MS,
        ) >= 0
    }

    /**
     * Sets the sampling frequency on a UAC 1.0 endpoint.
     *
     * UAC 2.0 devices ignore this: their rate lives on a clock-source entity and is set with a
     * different request against the control interface, which is work for the streaming milestone.
     */
    public fun setUac1SampleRate(alternate: UsbAudioAlternate, sampleRate: Int): Boolean {
        val open = connection ?: return false
        val payload = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte(),
        )
        return open.controlTransfer(
            REQUEST_TYPE_SET_ENDPOINT,
            REQUEST_SET_CUR,
            SAMPLING_FREQ_CONTROL shl 8,
            alternate.endpointAddress,
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS,
        ) >= 0
    }

    /** Parks the interfaces back on their zero-bandwidth setting and hands the device back. */
    public fun release() {
        val open = connection ?: return
        val device = parkedDevice

        // Park before releasing: leaving an interface in a streaming alternate keeps its
        // isochronous bandwidth reserved and upsets whatever picks the device up next.
        parkOnRelease.forEach { (interfaceNumber, idleAlternate) ->
            open.controlTransfer(
                REQUEST_TYPE_SET_INTERFACE,
                REQUEST_SET_INTERFACE,
                idleAlternate,
                interfaceNumber,
                null,
                0,
                CONTROL_TIMEOUT_MS,
            )
        }

        if (device != null) {
            claimedInterfaces.forEach { number ->
                (0 until device.interfaceCount)
                    .map(device::getInterface)
                    .firstOrNull { it.id == number }
                    ?.let(open::releaseInterface)
            }
        }
        open.close()

        connection = null
        claimedInterfaces = mutableListOf()
        parkedDevice = null
        parkOnRelease = emptyMap()
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 500

        // Standard SET_INTERFACE, directed at an interface recipient.
        const val REQUEST_TYPE_SET_INTERFACE = 0x01
        const val REQUEST_SET_INTERFACE = 0x0B

        // Class request, directed at an endpoint.
        const val REQUEST_TYPE_SET_ENDPOINT = 0x22
        const val REQUEST_SET_CUR = 0x01
        const val SAMPLING_FREQ_CONTROL = 0x01
    }
}
