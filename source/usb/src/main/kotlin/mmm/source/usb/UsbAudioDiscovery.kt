package mmm.source.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import mmm.usb.UsbAudioDescriptorParser
import mmm.usb.UsbAudioFunction

/** A connected USB device that exposes an audio function we could drive ourselves. */
public data class UsbAudioCandidate(
    val device: UsbDevice,
    val function: UsbAudioFunction,
) {
    public val deviceName: String get() = device.deviceName

    public val label: String
        get() = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .ifBlank { "USB 오디오 장치 %04x:%04x".format(device.vendorId, device.productId) }

    public val identity: String get() = "%04x:%04x".format(device.vendorId, device.productId)
}

/**
 * Finds USB DACs and reads what they can do.
 *
 * Descriptors are read straight off the connection rather than reconstructed from Android's
 * [UsbDevice] objects: the platform classes expose interfaces and endpoints but drop every
 * class-specific descriptor, which is precisely where the audio formats and sample rates live.
 */
public class UsbAudioDiscovery(context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Every attached device that declares an audio-class interface. */
    public fun attachedAudioDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == USB_CLASS_AUDIO }
        }

    public fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Opens [device] long enough to read and parse its descriptors.
     *
     * @return null when permission has not been granted or the device could not be opened - both
     *   ordinary situations rather than errors, since the user may simply not have said yes yet.
     */
    public fun inspect(device: UsbDevice): UsbAudioCandidate? {
        if (!usbManager.hasPermission(device)) return null
        val connection = usbManager.openDevice(device) ?: return null
        return try {
            val raw = connection.rawDescriptors ?: return null
            UsbAudioCandidate(device, UsbAudioDescriptorParser.parse(raw))
        } finally {
            connection.close()
        }
    }

    /** Inspects everything attached, skipping devices we have no permission for. */
    public fun inspectAll(): List<UsbAudioCandidate> =
        attachedAudioDevices().mapNotNull(::inspect).filter { !it.function.isEmpty }

    /**
     * Whether Android currently has a USB audio output of its own.
     *
     * This is the observable that says whether an exclusive claim worked: while the platform's
     * driver holds the DAC it appears here, and once the driver has been detached it does not.
     */
    public fun androidSeesUsbOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    /** Names of the outputs Android is currently willing to use, for the diagnostics readout. */
    public fun androidOutputs(): List<String> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 장치"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 액세서리"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "내장 스피커"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선"
                else -> "기타(${device.type})"
            }
            "$kind: ${device.productName}"
        }

    public companion object {
        private const val USB_CLASS_AUDIO = 1
    }
}
