package mmm.app.ui.player

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mmm.app.MmmApplication
import mmm.app.player.PlayerConnection

/** Thumbnails for list rows, kept in memory so scrolling back does not decode them again. */
private object ThumbnailCache {
    val cache = LruCache<Long, ImageBitmap>(200)
    val missing = HashSet<Long>()
}

/**
 * A track's artwork, or a gradient tile with a note when it has none. [bitmap], when given, is
 * used as is - the now-playing view already has the large version.
 */
@Composable
fun Artwork(
    trackId: Long?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    bitmap: ImageBitmap? = null,
) {
    val app = LocalContext.current.applicationContext as MmmApplication
    val loaded by produceState(initialValue = bitmap ?: trackId?.let { ThumbnailCache.cache.get(it) }, trackId, bitmap) {
        if (bitmap != null) {
            value = bitmap
            return@produceState
        }
        val id = trackId ?: return@produceState
        if (ThumbnailCache.cache.get(id) != null || id in ThumbnailCache.missing) return@produceState
        val decoded = withContext(Dispatchers.IO) { PlayerConnection.artwork(app, id, THUMB_SIZE)?.asImageBitmap() }
        if (decoded == null) ThumbnailCache.missing += id else ThumbnailCache.cache.put(id, decoded)
        value = decoded
    }
    val shape = RoundedCornerShape(corner)
    val image = loaded
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF6A5ACD), Color(0xFF2B2D5C)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

private const val THUMB_SIZE = 160
