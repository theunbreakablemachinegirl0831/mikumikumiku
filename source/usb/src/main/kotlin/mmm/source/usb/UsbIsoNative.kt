package mmm.source.usb

import java.nio.ByteBuffer

/**
 * The usbfs calls behind isochronous streaming, implemented in `src/main/cpp/usb_iso.cpp`.
 *
 * Errors come back as negative errno values, the way the kernel reports them, so the telemetry
 * can say which one it was rather than just "failed".
 */
internal object UsbIsoNative {
    init {
        System.loadLibrary("mmmusb")
    }

    /** Linux `enum usb_device_speed` values. */
    const val SPEED_FULL = 2
    const val SPEED_HIGH = 3

    /** @return a handle, or 0 if the arguments were bad or memory ran out */
    @JvmStatic external fun nativeOpen(
        fd: Int,
        endpoint: Int,
        urbCount: Int,
        packetsPerUrb: Int,
        maxPacketBytes: Int,
    ): Long

    @JvmStatic external fun nativeBuffer(handle: Long, index: Int): ByteBuffer?

    /** @return 0 or -errno */
    @JvmStatic external fun nativeSubmit(handle: Long, index: Int, lengths: IntArray, packets: Int): Int

    /**
     * @param result receives { URB status, failed packets, bytes sent }
     * @return the completed URB's index, or -errno (-EAGAIN on timeout)
     */
    @JvmStatic external fun nativeReap(handle: Long, timeoutMs: Int, result: IntArray): Int

    @JvmStatic external fun nativeDiscardAll(handle: Long)

    @JvmStatic external fun nativeClose(handle: Long)

    /** @return the bus speed as [SPEED_FULL] / [SPEED_HIGH] / ..., or -errno */
    @JvmStatic external fun nativeSpeed(fd: Int): Int
}
