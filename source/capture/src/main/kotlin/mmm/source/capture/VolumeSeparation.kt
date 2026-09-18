package mmm.source.capture

import android.content.Context
import android.media.AudioManager
import mmm.audio.OutputRoute

/**
 * Strategy A for keeping the source app out of the listener's ears: play the processed signal on
 * the voice-call stream, then turn the media stream down to zero.
 *
 * Whether this works at all hinges on something Android does not document clearly - whether
 * `AudioPlaybackCapture` taps a track before or after the media stream volume is applied. If it
 * taps after, muting the source mutes what we capture and the whole strategy collapses to silence.
 * [captureSurvivesMuting] is how the diagnostics screen finds out on the actual device instead of
 * us guessing.
 */
public class VolumeSeparation(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMediaVolume: Int? = null

    public val mediaVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    public val maxMediaVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    public val voiceVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

    public val maxVoiceVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

    /**
     * Silences the source app's own output and brings the voice-call stream up to a working level.
     * The previous media volume is remembered so [restore] can put it back.
     */
    public fun engage(voiceVolumeFraction: Double = 0.7) {
        if (savedMediaVolume == null) savedMediaVolume = mediaVolume
        setStream(AudioManager.STREAM_MUSIC, 0)
        setStream(
            AudioManager.STREAM_VOICE_CALL,
            (maxVoiceVolume * voiceVolumeFraction).toInt().coerceIn(1, maxVoiceVolume),
        )
    }

    public fun restore() {
        savedMediaVolume?.let { setStream(AudioManager.STREAM_MUSIC, it) }
        savedMediaVolume = null
    }

    public fun setRouteVolume(route: OutputRoute, fraction: Double) {
        val stream = route.volumeStream()
        val max = audioManager.getStreamMaxVolume(stream)
        setStream(stream, (max * fraction).toInt().coerceIn(0, max))
    }

    private fun setStream(stream: Int, value: Int) {
        runCatching { audioManager.setStreamVolume(stream, value, 0) }
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
