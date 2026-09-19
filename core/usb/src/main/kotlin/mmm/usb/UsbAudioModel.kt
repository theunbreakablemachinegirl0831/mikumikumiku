package mmm.usb

/** Which revision of the USB Audio Class an interface speaks. */
public enum class UsbAudioSpec(public val protocol: Int, public val label: String) {
    UAC1(0x00, "UAC 1.0"),
    UAC2(0x20, "UAC 2.0"),
    ;

    public companion object {
        public fun fromProtocol(protocol: Int): UsbAudioSpec =
            entries.firstOrNull { it.protocol == protocol } ?: UAC1
    }
}

/**
 * How the endpoint keeps its clock in step with the host.
 *
 * This decides who owns the sample rate. An [ASYNCHRONOUS] DAC runs on its own crystal and tells
 * us, through a feedback endpoint, how much to send; an [ADAPTIVE] one follows whatever rate we
 * feed it. Getting this wrong does not fail loudly - it drifts, and drift on a listening test
 * shows up as clicks minutes into a session.
 */
public enum class UsbSyncType(public val code: Int) {
    NONE(0),
    ASYNCHRONOUS(1),
    ADAPTIVE(2),
    SYNCHRONOUS(3),
    ;

    public companion object {
        public fun fromCode(code: Int): UsbSyncType = entries.first { it.code == code and 0x03 }
    }
}

/** A PCM format an alternate setting can carry. */
public data class UsbPcmFormat(
    val channels: Int,
    /** Bytes occupied per sample in the stream (the "subslot"/"subframe" size). */
    val bytesPerSample: Int,
    /** Meaningful bits inside those bytes; 24-in-32 is common, so this is not always 8x the above. */
    val bitResolution: Int,
) {
    val bitsPerFrame: Int get() = channels * bytesPerSample * 8

    public fun bytesPerFrame(): Int = channels * bytesPerSample

    override fun toString(): String =
        "${channels}ch ${bitResolution}bit" +
            if (bitResolution != bytesPerSample * 8) " (in ${bytesPerSample * 8})" else ""
}

/**
 * One alternate setting of an audio-streaming interface: a format the device can be switched into.
 *
 * A USB DAC advertises its capabilities as a list of these, and driving it means picking one,
 * issuing SET_INTERFACE, and streaming to its endpoint.
 */
public data class UsbAudioAlternate(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val spec: UsbAudioSpec,
    val format: UsbPcmFormat,
    /**
     * Rates the descriptors advertise. Empty on UAC 2.0, where rates live in a clock-source entity
     * that has to be queried with a control request instead of read from the descriptor blob.
     */
    val sampleRates: List<Int>,
    val continuousRateRange: IntRange? = null,
    val endpointAddress: Int = 0,
    val syncType: UsbSyncType = UsbSyncType.NONE,
    /** Bytes the endpoint accepts per (micro)frame. Bounds how much we can send per interval. */
    val maxPacketSize: Int = 0,
    /** Additional isochronous transactions per microframe, from the high-bandwidth bits. */
    val transactionsPerMicroframe: Int = 1,
    val interval: Int = 1,
    /** Address of the explicit feedback endpoint, if the device provides one. */
    val feedbackEndpointAddress: Int? = null,
) {
    /** True when the device clocks itself and we must follow its feedback. */
    public val needsFeedback: Boolean get() = syncType == UsbSyncType.ASYNCHRONOUS

    public fun supportsRate(rate: Int): Boolean = when {
        sampleRates.isNotEmpty() -> rate in sampleRates
        continuousRateRange != null -> rate in continuousRateRange
        // UAC 2.0 leaves rates to the clock source, so the descriptor cannot rule anything out.
        else -> true
    }

    /** Bytes per (micro)frame needed to carry [rate], before any feedback adjustment. */
    public fun bytesPerInterval(rate: Int, microframes: Boolean): Int {
        val intervalsPerSecond = if (microframes) 8000 else 1000
        val framesPerInterval = kotlin.math.ceil(rate.toDouble() / intervalsPerSecond).toInt()
        return framesPerInterval * format.bytesPerFrame()
    }

    public val describe: String
        get() = buildString {
            append("alt ").append(alternateSetting).append(": ")
            append(format)
            append(" · ").append(syncType.name.lowercase())
            if (sampleRates.isNotEmpty()) {
                append(" · ").append(sampleRates.joinToString("/") { "${it / 1000}k" })
            } else if (continuousRateRange != null) {
                append(" · ").append(continuousRateRange.first).append("-").append(continuousRateRange.last)
            } else {
                append(" · rates from clock source")
            }
        }
}

/**
 * Everything we learned about a device's audio function from its descriptors.
 *
 * [zeroBandwidthAlternates] matters more than it looks: USB audio requires an alt setting with no
 * endpoint, which is where the interface must be parked when not streaming. Leaving a device in a
 * streaming alt setting keeps its isochronous bandwidth reserved and upsets the next app to use it.
 */
public data class UsbAudioFunction(
    val controlInterfaceNumber: Int?,
    val spec: UsbAudioSpec,
    val outputAlternates: List<UsbAudioAlternate>,
    val zeroBandwidthAlternates: Map<Int, Int>,
) {
    public val streamingInterfaceNumbers: List<Int>
        get() = outputAlternates.map { it.interfaceNumber }.distinct()

    public val isEmpty: Boolean get() = outputAlternates.isEmpty()

    /**
     * Picks the alternate setting that best matches what we want to play.
     *
     * Preference order is deliberate: the requested rate first, then the requested channel count,
     * then the highest bit depth. Resolution is last because playing 24-bit at the wrong sample
     * rate means resampling, and a resampler in the chain is a confound in a listening test in a
     * way that a few bits of dither is not.
     */
    public fun bestMatch(
        sampleRate: Int,
        channels: Int = 2,
        preferHighestResolution: Boolean = true,
    ): UsbAudioAlternate? {
        val candidates = outputAlternates.filter { it.format.channels == channels }
            .ifEmpty { outputAlternates }
        if (candidates.isEmpty()) return null

        // Ordered best-first, so the winner is the minimum.
        val ranking = compareBy<UsbAudioAlternate>(
            { if (it.supportsRate(sampleRate)) 0 else 1 },
            { if (it.format.channels == channels) 0 else 1 },
            { if (preferHighestResolution) -it.format.bitResolution else it.format.bitResolution },
            { -it.format.bytesPerSample },
            { it.alternateSetting },
        )
        return candidates.minWithOrNull(ranking)
    }

    /** Every distinct sample rate any output alternate advertises, ascending. */
    public val advertisedRates: List<Int>
        get() = outputAlternates.flatMap { it.sampleRates }.distinct().sorted()
}
