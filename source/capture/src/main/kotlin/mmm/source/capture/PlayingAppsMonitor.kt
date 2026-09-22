package mmm.source.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A media stream Android currently knows to be playing. */
public data class PlayingStream(
    val usage: Int,
    val contentType: Int,
) {
    public val usageLabel: String
        get() = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "미디어"
            AudioAttributes.USAGE_GAME -> "게임"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "통화"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "시스템음"
            AudioAttributes.USAGE_UNKNOWN -> "미지정"
            else -> "기타 ($usage)"
        }

    /** Whether our capture configuration would pick this stream up at all. */
    public val capturedByUs: Boolean
        get() = usage == AudioAttributes.USAGE_MEDIA ||
            usage == AudioAttributes.USAGE_GAME ||
            usage == AudioAttributes.USAGE_UNKNOWN
}

/**
 * Watches what is playing, so the setup screen can say "nothing is playing" instead of leaving a
 * silent meter to be misread as "this app blocks capture".
 *
 * Android does not name the owning app here - [AudioPlaybackConfiguration] deliberately withholds
 * it from ordinary apps - so this reports stream *kinds*, not packages. That is still enough to
 * distinguish the two failure modes that matter.
 */
public class PlayingAppsMonitor(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _streams = MutableStateFlow<List<PlayingStream>>(emptyList())
    public val streams: StateFlow<List<PlayingStream>> = _streams.asStateFlow()

    /** True when at least one stream we would capture is currently playing. */
    public val anythingCapturable: Boolean
        get() = _streams.value.any { it.capturedByUs }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            _streams.value = configs.map { it.toPlayingStream() }
        }
    }

    public fun start() {
        audioManager.registerAudioPlaybackCallback(callback, handler)
        _streams.value = audioManager.activePlaybackConfigurations.map { it.toPlayingStream() }
    }

    public fun stop() {
        audioManager.unregisterAudioPlaybackCallback(callback)
    }

    private fun AudioPlaybackConfiguration.toPlayingStream(): PlayingStream = PlayingStream(
        usage = audioAttributes.usage,
        contentType = audioAttributes.contentType,
    )
}
