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
 * Strategy A for keeping the source app out of the listener's ears: play the processed signal on
 * the voice-call stream, then turn the media stream down to zero.
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

    public val voiceVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

    public val maxVoiceVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

    /** True when the device refuses per-stream volume control entirely, which rules out strategy A. */
    public val fixedVolumePolicy: Boolean get() = audioManager.isVolumeFixed

    /** The most recent attempt on the media stream, for the diagnostics readout. */
    public var lastMediaChange: VolumeChange? = null
        private set

    public var lastVoiceChange: VolumeChange? = null
        private set

    /**
     * Silences the source app's output and brings the voice-call stream up to a working level.
     *
     * @param communicationMode also switches the device into `MODE_IN_COMMUNICATION`. A
     *   `USAGE_VOICE_COMMUNICATION` track is not reliably governed by the voice-call stream
     *   otherwise - but the mode also changes routing (it can prefer the earpiece), so it is a
     *   separate switch the diagnostics screen can toggle rather than an assumption.
     * @return true when the media stream really did reach zero.
     */
    public fun engage(
        voiceVolumeFraction: Double = 0.7,
        communicationMode: Boolean = false,
    ): Boolean {
        if (savedMediaVolume == null) savedMediaVolume = mediaVolume

        if (communicationMode && savedMode == null) {
            savedMode = audioManager.mode
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        }

        lastVoiceChange = setStream(
            AudioManager.STREAM_VOICE_CALL,
            (maxVoiceVolume * voiceVolumeFraction).toInt().coerceIn(1, maxVoiceVolume),
        )
        val media = setStream(AudioManager.STREAM_MUSIC, 0)
        lastMediaChange = media
        return media.applied
    }

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
