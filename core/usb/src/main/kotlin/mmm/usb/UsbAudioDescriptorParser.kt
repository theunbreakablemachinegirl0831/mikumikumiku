package mmm.usb

/**
 * Reads a device's raw configuration descriptor blob and works out how to drive its DAC.
 *
 * Android hands over the whole configuration as bytes and offers no help beyond that, so this walks
 * the descriptor chain itself. It is written against the blob rather than against Android's USB
 * classes on purpose: the parsing is where the subtle mistakes live (UAC 1.0 and 2.0 disagree about
 * where nearly everything sits), and keeping it off the platform means it can be tested against
 * synthetic descriptors instead of against whatever DAC happens to be plugged in.
 */
public object UsbAudioDescriptorParser {

    // Standard descriptor types.
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24
    private const val DESC_CS_ENDPOINT = 0x25

    // Interface classes and subclasses.
    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIOCONTROL = 0x01
    private const val SUBCLASS_AUDIOSTREAMING = 0x02

    // Class-specific AS interface descriptor subtypes.
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    // Endpoint attribute masks.
    private const val TRANSFER_TYPE_MASK = 0x03
    private const val TRANSFER_ISOCHRONOUS = 0x01
    private const val SYNC_TYPE_SHIFT = 2
    private const val USAGE_TYPE_MASK = 0x30
    private const val USAGE_FEEDBACK = 0x10
    private const val DIRECTION_IN = 0x80

    /**
     * @param raw the full configuration descriptor, exactly as `UsbDeviceConnection.getRawDescriptors`
     *   returns it minus the leading device descriptor, or the whole blob - leading bytes that are
     *   not interface descriptors are skipped harmlessly.
     */
    public fun parse(raw: ByteArray): UsbAudioFunction {
        val outputs = mutableListOf<UsbAudioAlternate>()
        val zeroBandwidth = mutableMapOf<Int, Int>()
        var controlInterface: Int? = null
        var spec = UsbAudioSpec.UAC1

        // State for the interface currently being walked.
        var inStreamingInterface = false
        var interfaceNumber = -1
        var alternateSetting = -1
        var interfaceSpec = UsbAudioSpec.UAC1
        var declaredEndpoints = 0

        var pendingFormat: UsbPcmFormat? = null
        var pendingRates: List<Int> = emptyList()
        var pendingRange: IntRange? = null
        var pendingUac2Channels: Int? = null
        var dataEndpoint: Int? = null
        var syncType = UsbSyncType.NONE
        var maxPacketSize = 0
        var transactions = 1
        var interval = 1
        var feedbackEndpoint: Int? = null

        fun flushInterface() {
            if (!inStreamingInterface) return
            if (declaredEndpoints == 0) {
                // The mandatory "park here when idle" setting.
                zeroBandwidth[interfaceNumber] = alternateSetting
                return
            }
            val format = pendingFormat ?: return
            val endpoint = dataEndpoint ?: return
            outputs += UsbAudioAlternate(
                interfaceNumber = interfaceNumber,
                alternateSetting = alternateSetting,
                spec = interfaceSpec,
                format = format,
                sampleRates = pendingRates,
                continuousRateRange = pendingRange,
                endpointAddress = endpoint,
                syncType = syncType,
                maxPacketSize = maxPacketSize,
                transactionsPerMicroframe = transactions,
                interval = interval,
                feedbackEndpointAddress = feedbackEndpoint,
            )
        }

        fun resetInterfaceState() {
            pendingFormat = null
            pendingRates = emptyList()
            pendingRange = null
            pendingUac2Channels = null
            dataEndpoint = null
            syncType = UsbSyncType.NONE
            maxPacketSize = 0
            transactions = 1
            interval = 1
            feedbackEndpoint = null
        }

        var i = 0
        while (i + 1 < raw.size) {
            val length = raw[i].toInt() and 0xFF
            if (length < 2 || i + length > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF

            when (type) {
                DESC_INTERFACE -> {
                    flushInterface()
                    resetInterfaceState()

                    val number = raw.u8(i + 2)
                    val alt = raw.u8(i + 3)
                    val endpoints = raw.u8(i + 4)
                    val clazz = raw.u8(i + 5)
                    val subclass = raw.u8(i + 6)
                    val protocol = raw.u8(i + 7)

                    if (clazz == CLASS_AUDIO && subclass == SUBCLASS_AUDIOCONTROL) {
                        controlInterface = number
                        spec = UsbAudioSpec.fromProtocol(protocol)
                    }
                    inStreamingInterface = clazz == CLASS_AUDIO && subclass == SUBCLASS_AUDIOSTREAMING
                    interfaceNumber = number
                    alternateSetting = alt
                    declaredEndpoints = endpoints
                    interfaceSpec = if (clazz == CLASS_AUDIO) UsbAudioSpec.fromProtocol(protocol) else spec
                }

                DESC_CS_INTERFACE -> if (inStreamingInterface && length >= 3) {
                    when (raw.u8(i + 2)) {
                        AS_GENERAL ->
                            // Only UAC 2.0 carries the channel count here; UAC 1.0 puts it in the
                            // format descriptor, so reading it unconditionally would pick up
                            // wFormatTag bytes and invent a channel count out of them.
                            if (interfaceSpec == UsbAudioSpec.UAC2 && length >= 16) {
                                pendingUac2Channels = raw.u8(i + 10)
                            }

                        AS_FORMAT_TYPE -> if (length >= 4 && raw.u8(i + 3) == FORMAT_TYPE_I) {
                            if (interfaceSpec == UsbAudioSpec.UAC2) {
                                if (length >= 6) {
                                    pendingFormat = UsbPcmFormat(
                                        channels = pendingUac2Channels ?: 2,
                                        bytesPerSample = raw.u8(i + 4),
                                        bitResolution = raw.u8(i + 5),
                                    )
                                }
                            } else if (length >= 8) {
                                pendingFormat = UsbPcmFormat(
                                    channels = raw.u8(i + 4),
                                    bytesPerSample = raw.u8(i + 5),
                                    bitResolution = raw.u8(i + 6),
                                )
                                val rateCount = raw.u8(i + 7)
                                if (rateCount == 0) {
                                    // Continuous: exactly two 24-bit values, lower then upper.
                                    if (length >= 14) {
                                        pendingRange = raw.u24(i + 8)..raw.u24(i + 11)
                                    }
                                } else {
                                    pendingRates = (0 until rateCount)
                                        .map { raw.u24(i + 8 + it * 3) }
                                        .filter { it > 0 }
                                }
                            }
                        }
                    }
                }

                DESC_ENDPOINT -> if (inStreamingInterface && length >= 7) {
                    val address = raw.u8(i + 2)
                    val attributes = raw.u8(i + 3)
                    val isIsochronous = attributes and TRANSFER_TYPE_MASK == TRANSFER_ISOCHRONOUS
                    val isFeedback = attributes and USAGE_TYPE_MASK == USAGE_FEEDBACK
                    val packet = raw.u16(i + 4)

                    when {
                        !isIsochronous -> Unit
                        isFeedback || address and DIRECTION_IN != 0 -> feedbackEndpoint = address
                        else -> {
                            dataEndpoint = address
                            syncType = UsbSyncType.fromCode(attributes shr SYNC_TYPE_SHIFT)
                            maxPacketSize = packet and 0x07FF
                            transactions = ((packet shr 11) and 0x03) + 1
                            interval = raw.u8(i + 6)
                        }
                    }
                }

                DESC_CS_ENDPOINT -> Unit
            }
            i += length
        }
        flushInterface()

        return UsbAudioFunction(
            controlInterfaceNumber = controlInterface,
            spec = spec,
            outputAlternates = outputs,
            zeroBandwidthAlternates = zeroBandwidth,
        )
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    private fun ByteArray.u24(index: Int): Int =
        u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)
}
