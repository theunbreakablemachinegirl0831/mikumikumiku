@file:OptIn(UnstableApi::class)

package mmm.app.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mmm.app.MmmApplication

/**
 * Background music playback with the earphone EQ and the spectrum tap in the audio path.
 *
 * A MediaSessionService, so the system gives it the media notification, lock-screen and headset
 * controls, and keeps it running with the screen off. ExoPlayer's own audio sink carries the two
 * processors; the output is an ordinary Android one, so it plays to whatever is connected -
 * Bluetooth included - with the EQ for the earphone chosen in the app.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val eq = EqAudioProcessor()
    private val tap = SpectrumTapProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // Float output would bypass the processors for high-resolution files; 16-bit
                // through the EQ is the point of this player.
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(eq, tap))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player)
            .setCallback(ResolvingCallback())
            .build()

        val app = application as MmmApplication
        scope.launch { app.activeCorrection.collect { eq.setProfile(it) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Makes every requested item playable. The default refuses - silently, from the screen's point
     * of view - any item that arrives without its URI, so the URI is rebuilt from the request
     * metadata or, failing that, from the MediaStore id the item carries.
     */
    private class ResolvingCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) return@mapNotNull item
                val uri = item.requestMetadata.mediaUri
                    ?: item.mediaId.toLongOrNull()?.let {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
                    }
                    ?: return@mapNotNull null
                item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    /** Swiping the app away stops the music only if it was not playing; otherwise it plays on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
