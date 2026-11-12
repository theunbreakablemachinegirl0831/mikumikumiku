package mmm.source.capture

import android.content.Context
import android.media.AudioManager
import mmm.audio.OutputRoute

/** What actually happened when we asked for a stream volume change. */
public data class VolumeChange(
    val requested: Int,
    val actual: Int,
    val max: Int,
    val error: String? = null,
) {
    public val applied: Boolean get() = error == null && actual == requested

    /**
     * True when the request was accepted but the device ignored it - a fixed-volume policy, or a
     * route (HDMI, some USB and Bluetooth devices) whose level Android does not control.
     */
    public val silentlyIgnored: Boolean get() = error == null && actual != requested
}

/**
 * Strategy A for keeping the source app out of the listener's ears: play the processed signal on a
 * stream of our own, then turn the media stream down to zero.
 *
 * Which stream matters more than it looks. Over Bluetooth the voice-call stream leaves A2DP for
 * HFP/SCO - narrowband mono, useless for a listening test and often silent unless SCO was started
 * explicitly - which is exactly how the first on-device run failed. The alarm and system streams
 * stay on A2DP, carry full quality over LDAC, and have their own volume slider, so they are the
 * routes that actually separate on a Bluetooth DAC.
 *
 * Two separate things can defeat this, and telling them apart is the whole job:
 *
 * 1. **The mute never happens.** `setStreamVolume` can throw (Do Not Disturb without
 *    `ACCESS_NOTIFICATION_POLICY`) or be quietly ignored (a fixed-volume route). Swallowing that
 *    would leave the UI claiming the source is muted while it plays on.
 * 2. **The mute happens and takes the capture with it.** Android does not document whether
 *    `AudioPlaybackCapture` taps a track before or after stream volume is applied. If it taps
 *    after, muting the source mutes what we capture and the strategy collapses to silence.
 *
 * So every change is read back and reported, and the caller decides which failure it is looking at.
 */
public class VolumeSeparation(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMediaVolume: Int? = null
    private var savedMode: Int? = null

    public val mediaVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    public val maxMediaVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** True when the device refuses per-stream volume control entirely, which rules out strategy A. */
    public val fixedVolumePolicy: Boolean get() = audioManager.isVolumeFixed

    /** The most recent attempt on the media stream, for the diagnostics readout. */
    public var lastMediaChange: VolumeChange? = null
        private set

    /** The most recent attempt on whichever stream our own output plays on. */
    public var lastOutputChange: VolumeChange? = null
        private set

    /**
     * Silences the source app's output and brings [route]'s own stream up to a working level.
     *
     * @param route must be one that plays on a stream other than media, or muting media would
     *   take our output with it.
     * @param communicationMode also switches the device into `MODE_IN_COMMUNICATION`. Only the
     *   voice-call route needs it, and the mode can re-route playback (it may prefer the earpiece),
     *   so it is a switch the diagnostics screen offers rather than an assumption baked in.
     * @return true when the media stream really did reach zero.
     */
    public fun engage(
        route: OutputRoute,
        outputVolumeFraction: Double = 0.7,
        communicationMode: Boolean = false,
    ): Boolean {
        require(route.separatesByStream) {
            "$route shares the media stream, so muting it would silence our own output too"
        }
        if (savedMediaVolume == null) savedMediaVolume = mediaVolume

        // Only the voice-call route needs the mode change, and it is the route that costs the most
        // when the mode re-routes playback, so it is never applied to the others.
        if (communicationMode && route == OutputRoute.VOICE_CALL && savedMode == null) {
            savedMode = audioManager.mode
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        }

        val stream = route.volumeStream()
        val max = audioManager.getStreamMaxVolume(stream)
        lastOutputChange = setStream(
            stream,
            (max * outputVolumeFraction).toInt().coerceIn(1, max),
        )
        val media = setStream(AudioManager.STREAM_MUSIC, 0)
        lastMediaChange = media
        return media.applied
    }

    /** Current volume of whichever stream [route] plays on. */
    public fun outputVolume(route: OutputRoute): Int =
        audioManager.getStreamVolume(route.volumeStream())

    public fun maxOutputVolume(route: OutputRoute): Int =
        audioManager.getStreamMaxVolume(route.volumeStream())

    public fun restore() {
        savedMediaVolume?.let { lastMediaChange = setStream(AudioManager.STREAM_MUSIC, it) }
        savedMediaVolume = null
        savedMode?.let { mode -> runCatching { audioManager.mode = mode } }
        savedMode = null
    }

    public fun setRouteVolume(route: OutputRoute, fraction: Double): VolumeChange {
        val stream = route.volumeStream()
        val max = audioManager.getStreamMaxVolume(stream)
        return setStream(stream, (max * fraction).toInt().coerceIn(0, max))
    }

    /** Applies a stream volume and reads it back, so a rejected or ignored request is visible. */
    private fun setStream(stream: Int, value: Int): VolumeChange {
        val max = audioManager.getStreamMaxVolume(stream)
        val error = try {
            audioManager.setStreamVolume(stream, value, 0)
            null
        } catch (e: SecurityException) {
            // The usual cause: Do Not Disturb is on and we lack ACCESS_NOTIFICATION_POLICY.
            "볼륨 변경이 거부되었다 (방해 금지 모드 때문일 수 있다): ${e.message}"
        } catch (e: Exception) {
            "볼륨 변경 실패: ${e.message}"
        }
        return VolumeChange(
            requested = value,
            actual = audioManager.getStreamVolume(stream),
            max = max,
            error = error,
        )
    }

    public companion object {
        /**
         * The question the diagnostics build has to answer: with the media stream at zero, does the
         * capture meter still show signal?
         *
         * @param sawSignalBeforeMuting whether capture was producing audio with media volume up
         * @param sawSignalAfterMuting whether it still does with media volume at zero
         */
        public fun captureSurvivesMuting(
            sawSignalBeforeMuting: Boolean,
            sawSignalAfterMuting: Boolean,
        ): Boolean = sawSignalBeforeMuting && sawSignalAfterMuting
    }
}
