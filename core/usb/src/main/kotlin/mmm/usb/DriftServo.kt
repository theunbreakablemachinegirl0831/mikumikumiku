package mmm.usb

/**
 * Keeps the buffer between a clocked producer and the USB bus at a steady depth.
 *
 * In live mode the audio arrives on the phone's audio clock and leaves on the USB bus clock, and no
 * two crystals agree: a 50 ppm difference is 2.4 frames a second at 48 kHz, which empties a 40 ms
 * buffer in under a quarter of an hour. The DAC has no feedback endpoint to tell us its rate, so
 * the only thing we can watch is our own backlog.
 *
 * The response is to send one frame more or less in an occasional packet. No sample is dropped or
 * repeated, so there is nothing to hear; the DAC just sees a packet of 49 instead of 48.
 *
 * The backlog jitters by a whole processing block every time the producer writes, so it is smoothed
 * over about a second before being compared, and nothing happens inside [deadbandFrames] of the
 * target. That leaves the servo idle most of the time and correcting at roughly the drift rate when
 * it is not - see the simulation in the tests.
 */
public class DriftServo(
    public val targetFrames: Int,
    public val deadbandFrames: Int,
    packetsPerSecond: Int,
    smoothingSeconds: Double = 1.0,
    /** Upper bound on corrections; 20 per second at 48 kHz covers a 400 ppm clock mismatch. */
    maxCorrectionsPerSecond: Double = 20.0,
) {
    init {
        require(targetFrames > 0) { "target must be positive" }
        require(deadbandFrames >= 0) { "deadband must not be negative" }
    }

    private val alpha = 1.0 / (smoothingSeconds * packetsPerSecond).coerceAtLeast(1.0)
    private val minSpacing = (packetsPerSecond / maxCorrectionsPerSecond).toInt().coerceAtLeast(1)

    private var smoothed = Double.NaN
    private var sinceLast = Int.MAX_VALUE

    /** The smoothed backlog, for telemetry. */
    public val smoothedFrames: Double get() = if (smoothed.isNaN()) 0.0 else smoothed

    /** Corrections made so far, by direction. Their difference over time is the measured drift. */
    public var fasterCount: Long = 0L
        private set
    public var slowerCount: Long = 0L
        private set

    /**
     * Called once per packet with the frames currently buffered.
     * @return +1 to drain one extra frame, -1 to hold one back, 0 otherwise
     */
    public fun update(bufferedFrames: Int): Int {
        smoothed = if (smoothed.isNaN()) {
            bufferedFrames.toDouble()
        } else {
            smoothed + alpha * (bufferedFrames - smoothed)
        }
        if (sinceLast < Int.MAX_VALUE) sinceLast++
        if (sinceLast < minSpacing) return 0

        val error = smoothed - targetFrames
        val correction = when {
            error > deadbandFrames -> 1
            error < -deadbandFrames -> -1
            else -> 0
        }
        if (correction != 0) {
            sinceLast = 0
            if (correction > 0) fasterCount++ else slowerCount++
        }
        return correction
    }

    public fun reset() {
        smoothed = Double.NaN
        sinceLast = Int.MAX_VALUE
        fasterCount = 0
        slowerCount = 0
    }
}
