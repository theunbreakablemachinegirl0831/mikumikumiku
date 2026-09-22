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
     * Whether Android still *lists* a USB audio output.
     *
     * This is a weaker signal than it looks, and taking it for the answer was a mistake: the list
     * enumerates what the platform knows is attached, not where audio is going. A claimed DAC
     * stays on it simply because it is still plugged in. Use [probeRoutedOutput] to find out what
     * is actually being played to.
     */
    public fun androidListsUsbOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isUsb }

    /**
     * Where audio would actually go right now.
     *
     * Opens a momentary silent track and asks what it got routed to. This is the observable that
     * settles an exclusive claim: once the platform has lost the DAC it routes to the built-in
     * speaker instead, whatever its device list still says.
     */
    public fun probeRoutedOutput(settleMs: Long = 600): AudioDeviceInfo? {
        val format = android.media.AudioFormat.Builder()
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = android.media.AudioTrack
            .getMinBufferSize(48000, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(1024)

        val track = runCatching {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBytes)
                .build()
        }.getOrNull() ?: return null

        return try {
            // Reading the route straight after the first write returns the device the policy
            // assigned at track creation, which is stale whenever the route is in the middle of
            // changing - and that is exactly the moment we care about. Claiming a DAC removes its
            // sound card, and the fallback to the speaker takes a moment to propagate, so this
            // keeps feeding silence and re-reading until the answer stops moving.
            track.play()
            val silence = ShortArray(minBytes / 2)
            val deadline = System.currentTimeMillis() + settleMs
            var routed: AudioDeviceInfo? = null
            while (System.currentTimeMillis() < deadline) {
                track.write(silence, 0, silence.size)
                routed = track.routedDevice ?: routed
            }
            routed
        } catch (e: Exception) {
            null
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Human-readable form of [probeRoutedOutput], for the diagnostics readout. */
    public fun probeRoutedOutputLabel(): String? =
        probeRoutedOutput()?.let { "${describeType(it.type)}: ${it.productName}" }

    /** True when audio is currently being routed to a USB device. */
    public fun audioActuallyRoutedToUsb(): Boolean = probeRoutedOutput()?.isUsb == true

    private val AudioDeviceInfo.isUsb: Boolean
        get() = type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** Names of the outputs Android is currently willing to use, for the diagnostics readout. */
    public fun androidOutputs(): List<String> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { "${describeType(it.type)}: ${it.productName}" }

    private fun describeType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 장치"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 액세서리"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "내장 스피커"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선"
        else -> "기타($type)"
    }

    public companion object {
        private const val USB_CLASS_AUDIO = 1
    }
}
