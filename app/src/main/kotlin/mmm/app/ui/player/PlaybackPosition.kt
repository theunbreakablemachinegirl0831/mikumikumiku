package mmm.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** How often a moving position is read again for the screen. */
const val POSITION_TICK_MS = 50L

/**
 * The playback position for a progress bar or a time, read again every [POSITION_TICK_MS] while
 * [running] and while the screen is started.
 *
 * Every tick asks the player where it is ([read]) rather than adding 50 ms to the last value, so
 * no error builds up however long it runs, and a late tick simply shows the true position a
 * little later. The same goes for this app's player and another app's: both are a question to
 * the player, answered from its own clock.
 *
 * Only what reads the returned state's value redraws when it moves. Read it inside a draw block
 * or a small composable that shows nothing else, never in a whole screen.
 *
 * Nothing here touches the audio: this is how often the screen looks, not how the sound is
 * buffered.
 *
 * @param anchor anything that changes when the position jumps while stopped - a seek or a new
 *   track - so the shown value follows it without the clock running
 */
@Composable
fun rememberPlaybackPosition(running: Boolean, anchor: Any?, read: () -> Long): LongState {
    val position = remember { mutableLongStateOf(read()) }
    val currentRead by rememberUpdatedState(read)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, anchor, lifecycle) {
        position.longValue = currentRead()
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                position.longValue = currentRead()
                delay(POSITION_TICK_MS)
            }
        }
    }
    return position
}
