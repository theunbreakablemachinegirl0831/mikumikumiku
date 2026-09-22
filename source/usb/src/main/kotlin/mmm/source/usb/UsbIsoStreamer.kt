package mmm.source.usb

import android.os.Process
import android.system.Os
import android.system.OsConstants
import mmm.usb.DriftServo
import mmm.usb.IsoPacketScheduler
import mmm.usb.PcmPacker
import mmm.usb.SpscFloatRing
import mmm.usb.UsbAudioAlternate
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** What the USB stream is doing, for the live screen. */
public data class UsbStreamStats(
    val running: Boolean = false,
    /** True once enough audio arrived to start playing it; false while priming or starved. */
    val playing: Boolean = false,
    val sampleRate: Int = 0,
    val packetsPerSecond: Int = 0,
    val highSpeed: Boolean = false,
    val packets: Long = 0,
    val failedUrbs: Long = 0,
    val failedPackets: Long = 0,
    /** Times the buffer ran dry while playing. Each one is an audible gap. */
    val underruns: Long = 0,
    val fasterCorrections: Long = 0,
    val slowerCorrections: Long = 0,
    val bufferedFrames: Int = 0,
    val error: String? = null,
)

/**
 * Streams audio to a claimed USB DAC's isochronous endpoint.
 *
 * A feeder thread keeps [URB_COUNT] transfers queued with the kernel. Each time one completes it
 * is refilled from [ring] - packet sizes from [IsoPacketScheduler], nudged by [DriftServo] when
 * the producer runs on its own clock - and handed straight back. About [URB_MS] x [URB_COUNT]
 * milliseconds are in flight, which is the latency this stage adds on top of the ring.
 *
 * When the ring runs dry the stream does not stop: it sends silence and waits to re-prime. The
 * endpoint expects a packet every interval, and stopping and restarting the stream is far more
 * disruptive to a DAC than a gap.
 *
 * @param clockedProducer true when audio arrives on its own clock (live capture), which is when
 *   the drift servo is needed; false when the producer is paced by this stream (test tone)
 */
public class UsbIsoStreamer internal constructor(
    private val fd: Int,
    private val alternate: UsbAudioAlternate,
    public val sampleRate: Int,
    public val highSpeed: Boolean,
    clockedProducer: Boolean,
) {
    public val channels: Int = alternate.format.channels
    private val bytesPerFrame = alternate.format.bytesPerFrame()

    public val packetsPerSecond: Int =
        IsoPacketScheduler.packetsPerSecond(highSpeed, alternate.interval)
    private val packetsPerUrb = (packetsPerSecond * URB_MS / 1000).coerceIn(1, 128)
    private val maxPacketBytes = alternate.maxPacketSize * alternate.transactionsPerMicroframe.coerceAtLeast(1)

    private val scheduler = IsoPacketScheduler(
        sampleRate,
        packetsPerSecond,
        maxFramesPerPacket = maxPacketBytes / bytesPerFrame,
    )
    private val servo: DriftServo? = if (clockedProducer) {
        DriftServo(TARGET_FRAMES, DEADBAND_FRAMES, packetsPerSecond)
    } else {
        null
    }
    private val primeFrames = if (clockedProducer) TARGET_FRAMES else PULL_PRIME_FRAMES

    /** Where the sink puts processed audio for this stream to send. */
    public val ring: SpscFloatRing = SpscFloatRing(RING_FRAMES, channels)

    private val packer = PcmPacker(alternate.format)
    private val scratch = FloatArray((scheduler.maxFramesPerPacket) * channels)
    private val lengths = IntArray(packetsPerUrb)
    private val reapResult = IntArray(3)

    /** Audio waiting upstream of [ring], counted towards the servo's backlog. */
    @Volatile public var upstreamBacklog: () -> Int = { 0 }

    @Volatile private var running = false
    @Volatile private var playing = false
    @Volatile private var error: String? = null
    @Volatile private var packets = 0L
    @Volatile private var failedUrbs = 0L
    @Volatile private var failedPackets = 0L
    @Volatile private var underruns = 0L

    private var handle = 0L
    private var buffers: List<ByteBuffer> = emptyList()
    private var worker: Thread? = null

    public val isRunning: Boolean get() = running

    public fun stats(): UsbStreamStats = UsbStreamStats(
        running = running,
        playing = playing,
        sampleRate = sampleRate,
        packetsPerSecond = packetsPerSecond,
        highSpeed = highSpeed,
        packets = packets,
        failedUrbs = failedUrbs,
        failedPackets = failedPackets,
        underruns = underruns,
        fasterCorrections = servo?.fasterCount ?: 0,
        slowerCorrections = servo?.slowerCount ?: 0,
        bufferedFrames = ring.availableFrames + upstreamBacklog(),
        error = error,
    )

    /** @return false with [UsbStreamStats.error] set if the stream could not be set up */
    public fun start(): Boolean {
        if (running) return true
        handle = UsbIsoNative.nativeOpen(fd, alternate.endpointAddress, URB_COUNT, packetsPerUrb, maxPacketBytes)
        if (handle == 0L) {
            error = "URB를 준비하지 못했다 (fd=$fd, 패킷 최대 ${maxPacketBytes}바이트)"
            return false
        }
        buffers = (0 until URB_COUNT).map { index ->
            UsbIsoNative.nativeBuffer(handle, index) ?: run {
                UsbIsoNative.nativeClose(handle)
                handle = 0L
                error = "URB 버퍼를 받지 못했다"
                return false
            }
        }
        error = null
        running = true
        worker = thread(name = "mmm-usb-iso", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            feed()
        }
        return true
    }

    public fun stop() {
        running = false
        val feeder = worker
        worker = null
        feeder?.join(1000)
        if (feeder?.isAlive == true) {
            // Still inside a native call. Freeing the URBs under it would be worse than leaking them.
            error = "스트리머 스레드가 멈추지 않았다"
            return
        }
        if (handle != 0L) {
            UsbIsoNative.nativeClose(handle)
            handle = 0L
        }
        buffers = emptyList()
        playing = false
    }

    private fun feed() {
        for (index in 0 until URB_COUNT) {
            if (!fillAndSubmit(index)) {
                running = false
                return
            }
        }
        var silentPolls = 0
        while (running) {
            val index = UsbIsoNative.nativeReap(handle, REAP_TIMEOUT_MS, reapResult)
            when {
                index >= 0 -> {
                    silentPolls = 0
                    if (reapResult[0] != 0) failedUrbs++
                    failedPackets += reapResult[1]
                    if (!fillAndSubmit(index)) break
                }
                -index == OsConstants.EAGAIN -> {
                    // Transfers are queued but none has completed: the bus is not servicing them.
                    if (++silentPolls * REAP_TIMEOUT_MS >= 1000) {
                        error = "DAC이 전송을 받아 가지 않는다 (1초 동안 완료 0건)"
                    }
                }
                else -> {
                    error = describe("전송 회수 실패", -index)
                    break
                }
            }
        }
        running = false
        playing = false
    }

    private fun fillAndSubmit(index: Int): Boolean {
        val buffer = buffers[index]
        var offset = 0
        for (p in 0 until packetsPerUrb) {
            val correction = if (playing) servo?.update(ring.availableFrames + upstreamBacklog()) ?: 0 else 0
            val frames = scheduler.next(correction)

            if (!playing && ring.availableFrames >= primeFrames) playing = true

            val got = if (playing) ring.read(scratch, frames) else 0
            val bytes = if (got == 0) {
                packer.silence(frames, buffer, offset)
            } else {
                if (got < frames) scratch.fill(0f, got * channels, frames * channels)
                packer.pack(scratch, frames, buffer, offset)
            }
            if (playing && got < frames) {
                // Starved: send what there was and wait for the buffer to refill to its target,
                // rather than stuttering along at whatever depth is left.
                underruns++
                playing = false
                servo?.reset()
            }
            lengths[p] = bytes
            offset += bytes
        }

        val result = UsbIsoNative.nativeSubmit(handle, index, lengths, packetsPerUrb)
        if (result != 0) {
            error = describe("전송 제출 실패", -result)
            return false
        }
        packets += packetsPerUrb
        return true
    }

    private fun describe(what: String, errno: Int): String {
        val name = runCatching { OsConstants.errnoName(errno) }.getOrNull() ?: "errno $errno"
        val text = runCatching { Os.strerror(errno) }.getOrNull() ?: ""
        val hint = when (errno) {
            OsConstants.ENODEV, OsConstants.ESHUTDOWN -> " - DAC이 분리되었다"
            OsConstants.EINVAL, OsConstants.EMSGSIZE -> " - 패킷 크기나 엔드포인트가 맞지 않는다"
            OsConstants.EXDEV -> " - 전송 일정이 밀렸다"
            else -> ""
        }
        return "$what: $name $text$hint"
    }

    private companion object {
        const val URB_COUNT = 4
        const val URB_MS = 8
        const val REAP_TIMEOUT_MS = 100

        /** ~85 ms of room. The sink blocks when it is full, which is what paces a test tone. */
        const val RING_FRAMES = 4096

        /**
         * Backlog the servo holds in live mode, ~43 ms at 48 kHz: several processing blocks deep,
         * so one late block from the capture thread does not starve the bus.
         */
        const val TARGET_FRAMES = 2048

        /** Wider than the smoothed jitter of 512-frame blocks, so the servo is idle when clocks agree. */
        const val DEADBAND_FRAMES = 96

        const val PULL_PRIME_FRAMES = 1024
    }
}
