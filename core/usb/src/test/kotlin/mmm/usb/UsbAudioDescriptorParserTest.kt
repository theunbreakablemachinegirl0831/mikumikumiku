package mmm.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Descriptor blobs are assembled here rather than captured from a device, so the parser can be
 * held to the spec instead of to one DAC's quirks - and so a regression shows up on any machine
 * rather than only when hardware is plugged in.
 */
class UsbAudioDescriptorParserTest {

    private class Blob {
        private val bytes = mutableListOf<Byte>()

        fun raw(vararg values: Int) = apply { values.forEach { bytes += it.toByte() } }

        /** Standard interface descriptor. */
        fun iface(number: Int, alt: Int, endpoints: Int, clazz: Int, subclass: Int, protocol: Int) =
            raw(9, 0x04, number, alt, endpoints, clazz, subclass, protocol, 0)

        fun audioControl(number: Int, protocol: Int) = iface(number, 0, 0, 0x01, 0x01, protocol)

        fun audioStreaming(number: Int, alt: Int, endpoints: Int, protocol: Int) =
            iface(number, alt, endpoints, 0x01, 0x02, protocol)

        /** UAC 1.0 type-I format descriptor with discrete sample rates. */
        fun uac1Format(channels: Int, bytesPerSample: Int, bits: Int, rates: List<Int>) = apply {
            val length = 8 + rates.size * 3
            raw(length, 0x24, 0x02, 0x01, channels, bytesPerSample, bits, rates.size)
            rates.forEach { raw(it and 0xFF, (it shr 8) and 0xFF, (it shr 16) and 0xFF) }
        }

        fun uac1ContinuousFormat(channels: Int, bytesPerSample: Int, bits: Int, low: Int, high: Int) =
            apply {
                raw(14, 0x24, 0x02, 0x01, channels, bytesPerSample, bits, 0)
                raw(low and 0xFF, (low shr 8) and 0xFF, (low shr 16) and 0xFF)
                raw(high and 0xFF, (high shr 8) and 0xFF, (high shr 16) and 0xFF)
            }

        /** UAC 2.0 AS_GENERAL, which is where the channel count lives in 2.0. */
        fun uac2General(channels: Int) =
            raw(16, 0x24, 0x01, 1, 0, 1, 0x01, 0, 0, 0, channels, 0, 0, 0, 0, 0)

        fun uac2Format(bytesPerSample: Int, bits: Int) =
            raw(6, 0x24, 0x02, 0x01, bytesPerSample, bits)

        /** Isochronous endpoint. [attributes] carries transfer, sync and usage bits. */
        fun isoEndpoint(address: Int, attributes: Int, maxPacket: Int, interval: Int) =
            raw(9, 0x05, address, attributes, maxPacket and 0xFF, (maxPacket shr 8) and 0xFF, interval, 0, 0)

        fun build(): ByteArray = bytes.toByteArray()
    }

    // Isochronous + asynchronous sync + data usage.
    private val isoAsyncData = 0x01 or (1 shl 2)
    private val isoAdaptiveData = 0x01 or (2 shl 2)
    private val isoFeedback = 0x01 or 0x10

    @Test
    fun `a UAC 1 device's formats and rates are read`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 0, 0, 0x00)
            .audioStreaming(1, 1, 1, 0x00)
            .uac1Format(channels = 2, bytesPerSample = 2, bits = 16, rates = listOf(44100, 48000))
            .isoEndpoint(0x01, isoAdaptiveData, maxPacket = 200, interval = 1)
            .build()

        val function = UsbAudioDescriptorParser.parse(raw)

        assertEquals(0, function.controlInterfaceNumber)
        assertEquals(UsbAudioSpec.UAC1, function.spec)
        assertEquals(1, function.outputAlternates.size)

        val alt = function.outputAlternates.single()
        assertEquals(1, alt.interfaceNumber)
        assertEquals(1, alt.alternateSetting)
        assertEquals(UsbPcmFormat(channels = 2, bytesPerSample = 2, bitResolution = 16), alt.format)
        assertEquals(listOf(44100, 48000), alt.sampleRates)
        assertEquals(0x01, alt.endpointAddress)
        assertEquals(UsbSyncType.ADAPTIVE, alt.syncType)
        assertEquals(200, alt.maxPacketSize)
        assertTrue(!alt.needsFeedback)
    }

    @Test
    fun `the zero-bandwidth alternate is recorded so the interface can be parked`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 0, 0, 0x00)
            .audioStreaming(1, 1, 1, 0x00)
            .uac1Format(2, 2, 16, listOf(48000))
            .isoEndpoint(0x01, isoAdaptiveData, 200, 1)
            .build()

        val function = UsbAudioDescriptorParser.parse(raw)
        assertEquals(mapOf(1 to 0), function.zeroBandwidthAlternates)
    }

    @Test
    fun `an asynchronous endpoint and its feedback endpoint are paired up`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 0, 0, 0x00)
            .audioStreaming(1, 1, 2, 0x00)
            .uac1Format(2, 4, 24, listOf(96000))
            .isoEndpoint(0x01, isoAsyncData, maxPacket = 800, interval = 1)
            .isoEndpoint(0x81, isoFeedback, maxPacket = 4, interval = 4)
            .build()

        val alt = UsbAudioDescriptorParser.parse(raw).outputAlternates.single()
        assertEquals(UsbSyncType.ASYNCHRONOUS, alt.syncType)
        assertTrue(alt.needsFeedback, "an async DAC runs on its own clock and must be followed")
        assertEquals(0x81, alt.feedbackEndpointAddress)
        assertEquals(0x01, alt.endpointAddress, "the IN endpoint must not be mistaken for the data one")
        assertEquals(24, alt.format.bitResolution)
        assertEquals(4, alt.format.bytesPerSample)
    }

    @Test
    fun `UAC 2 takes its channel count from AS_GENERAL and leaves rates to the clock source`() {
        val raw = Blob()
            .audioControl(0, 0x20)
            .audioStreaming(1, 0, 0, 0x20)
            .audioStreaming(1, 1, 1, 0x20)
            .uac2General(channels = 2)
            .uac2Format(bytesPerSample = 4, bits = 32)
            .isoEndpoint(0x01, isoAsyncData, maxPacket = 1024, interval = 1)
            .build()

        val function = UsbAudioDescriptorParser.parse(raw)
        assertEquals(UsbAudioSpec.UAC2, function.spec)

        val alt = function.outputAlternates.single()
        assertEquals(2, alt.format.channels)
        assertEquals(32, alt.format.bitResolution)
        assertTrue(alt.sampleRates.isEmpty(), "UAC 2 rates come from a control request, not descriptors")
        // Nothing in the descriptors can rule a rate out, so none may be rejected here.
        assertTrue(alt.supportsRate(384000))
    }

    @Test
    fun `continuous rate ranges are understood`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 1, 1, 0x00)
            .uac1ContinuousFormat(2, 2, 16, low = 8000, high = 48000)
            .isoEndpoint(0x01, isoAdaptiveData, 200, 1)
            .build()

        val alt = UsbAudioDescriptorParser.parse(raw).outputAlternates.single()
        assertEquals(8000..48000, alt.continuousRateRange)
        assertTrue(alt.supportsRate(44100))
        assertTrue(!alt.supportsRate(96000))
    }

    @Test
    fun `input-only and non-audio interfaces are ignored`() {
        val raw = Blob()
            // A HID interface, of the sort a DAC with volume buttons also exposes.
            .iface(0, 0, 1, 0x03, 0x00, 0x00)
            .isoEndpoint(0x82, isoAdaptiveData, 64, 1)
            .audioControl(1, 0x00)
            .audioStreaming(2, 1, 1, 0x00)
            .uac1Format(2, 2, 16, listOf(48000))
            .isoEndpoint(0x02, isoAdaptiveData, 200, 1)
            .build()

        val function = UsbAudioDescriptorParser.parse(raw)
        assertEquals(1, function.outputAlternates.size)
        assertEquals(2, function.outputAlternates.single().interfaceNumber)
        assertEquals(1, function.controlInterfaceNumber)
    }

    @Test
    fun `a truncated blob stops cleanly instead of running off the end`() {
        val full = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 1, 1, 0x00)
            .uac1Format(2, 2, 16, listOf(48000))
            .isoEndpoint(0x01, isoAdaptiveData, 200, 1)
            .build()

        for (cut in 1 until full.size) {
            // Any prefix must parse without throwing; a device can be unplugged mid-read.
            UsbAudioDescriptorParser.parse(full.copyOfRange(0, cut))
        }
    }

    @Test
    fun `a device with no audio function reports empty rather than failing`() {
        val raw = Blob().iface(0, 0, 0, 0x03, 0x00, 0x00).build()
        val function = UsbAudioDescriptorParser.parse(raw)
        assertTrue(function.isEmpty)
        assertNull(function.controlInterfaceNumber)
        assertNull(function.bestMatch(48000))
    }

    @Test
    fun `the best match prefers the requested rate over a higher bit depth`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 0, 0, 0x00)
            // 24-bit, but only at 96 kHz.
            .audioStreaming(1, 1, 1, 0x00)
            .uac1Format(2, 4, 24, listOf(96000))
            .isoEndpoint(0x01, isoAsyncData, 800, 1)
            // 16-bit at the rate we actually want.
            .audioStreaming(1, 2, 1, 0x00)
            .uac1Format(2, 2, 16, listOf(48000))
            .isoEndpoint(0x01, isoAsyncData, 400, 1)
            .build()

        val function = UsbAudioDescriptorParser.parse(raw)
        val chosen = assertNotNull(function.bestMatch(sampleRate = 48000, channels = 2))
        assertEquals(
            2,
            chosen.alternateSetting,
            "resampling is a confound in a listening test; a few bits of depth is not",
        )

        val at96 = assertNotNull(function.bestMatch(sampleRate = 96000, channels = 2))
        assertEquals(1, at96.alternateSetting)
    }

    @Test
    fun `the best match prefers the highest resolution once the rate matches`() {
        val raw = Blob()
            .audioControl(0, 0x00)
            .audioStreaming(1, 1, 1, 0x00)
            .uac1Format(2, 2, 16, listOf(48000))
            .isoEndpoint(0x01, isoAsyncData, 400, 1)
            .audioStreaming(1, 2, 1, 0x00)
            .uac1Format(2, 4, 24, listOf(48000))
            .isoEndpoint(0x01, isoAsyncData, 800, 1)
            .build()

        val chosen = assertNotNull(UsbAudioDescriptorParser.parse(raw).bestMatch(48000))
        assertEquals(24, chosen.format.bitResolution)
    }

    @Test
    fun `packet budget follows the sample rate and frame size`() {
        val alt = UsbAudioAlternate(
            interfaceNumber = 1,
            alternateSetting = 1,
            spec = UsbAudioSpec.UAC2,
            format = UsbPcmFormat(channels = 2, bytesPerSample = 4, bitResolution = 24),
            sampleRates = emptyList(),
        )
        // 48 kHz over high-speed microframes: 6 frames each, 8 bytes per frame.
        assertEquals(48, alt.bytesPerInterval(48000, microframes = true))
        // Full speed puts a whole millisecond in one frame.
        assertEquals(384, alt.bytesPerInterval(48000, microframes = false))
        // A rate that does not divide evenly has to round up or the buffer starves.
        assertEquals(360, alt.bytesPerInterval(44100, microframes = false))
    }
}
