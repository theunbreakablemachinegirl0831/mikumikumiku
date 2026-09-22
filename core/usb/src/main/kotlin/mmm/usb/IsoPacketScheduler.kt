package mmm.usb

/**
 * Decides how many frames go into each isochronous packet.
 *
 * Without a feedback endpoint the host owns the rate, and the rate is whatever these counts add up
 * to. The nominal count is spread with an integer accumulator rather than rounded per packet, so
 * over any second the total is exactly the sample rate: 48 kHz at 1000 packets per second is 48
 * every time, and 44.1 kHz is nine 44s and a 45. A rounding scheme that is off by one frame per
 * second is a 20 ppm error, which is the same size as the clock drift this is meant to correct.
 *
 * A correction of +1 or -1 on top of the nominal count is how [DriftServo] nudges the rate.
 */
public class IsoPacketScheduler(
    public val sampleRate: Int,
    public val packetsPerSecond: Int,
    /** Largest packet the endpoint accepts, in frames. */
    public val maxFramesPerPacket: Int,
) {
    init {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(packetsPerSecond > 0) { "packet rate must be positive" }
        require(nominalMaxFrames(sampleRate, packetsPerSecond) <= maxFramesPerPacket) {
            "$sampleRate Hz needs up to ${nominalMaxFrames(sampleRate, packetsPerSecond)} frames per " +
                "packet but the endpoint takes $maxFramesPerPacket"
        }
    }

    private var remainder = 0L

    /** The correction the last packet actually received, after clamping to the endpoint's limit. */
    public var appliedCorrection: Int = 0
        private set

    /**
     * Frames for the next packet.
     *
     * @param correction frames to add to the nominal count; the servo only ever asks for -1, 0 or +1
     */
    public fun next(correction: Int = 0): Int {
        remainder += sampleRate
        val nominal = (remainder / packetsPerSecond).toInt()
        remainder -= nominal.toLong() * packetsPerSecond
        val frames = (nominal + correction).coerceIn(0, maxFramesPerPacket)
        appliedCorrection = frames - nominal
        return frames
    }

    public fun reset() {
        remainder = 0
        appliedCorrection = 0
    }

    public companion object {
        /**
         * Packets per second for an isochronous endpoint.
         *
         * A full-speed bus counts in 1 ms frames and a high-speed one in 125 us microframes; either
         * way the endpoint is serviced every 2^(bInterval-1) of them.
         */
        public fun packetsPerSecond(highSpeed: Boolean, bInterval: Int): Int {
            val base = if (highSpeed) 8000 else 1000
            val exponent = (bInterval.coerceIn(1, 16) - 1)
            return (base shr exponent).coerceAtLeast(1)
        }

        /** The biggest nominal packet this rate produces, before any correction. */
        public fun nominalMaxFrames(sampleRate: Int, packetsPerSecond: Int): Int =
            ((sampleRate + packetsPerSecond - 1) / packetsPerSecond)
    }
}
