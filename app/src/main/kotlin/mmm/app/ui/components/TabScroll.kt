package mmm.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Counts every time the listener picks a tab in the bottom panel, the one already open included.
 * A tab's screen compares it with the count it last saw and goes back to its top when they differ.
 */
val LocalTabVisit = compositionLocalOf { 0 }

/**
 * Runs [toTop] when the listener picks this tab again. Each tab keeps its own scroll position
 * while it is not on screen - that is saved with the rest of its state - and starts from the top
 * when it is picked, so a tab never opens half-way down a list from last time.
 */
@Composable
fun ScrollToTopOnTabVisit(toTop: suspend () -> Unit) {
    val visit = LocalTabVisit.current
    var seen by rememberSaveable { mutableIntStateOf(visit) }
    LaunchedEffect(visit) {
        if (visit != seen) {
            seen = visit
            toTop()
        }
    }
}

/** A tab screen's scroll position: its own, kept apart from every other tab's, back to the top on each visit. */
@Composable
fun rememberTabScrollState(): ScrollState {
    val state = rememberScrollState()
    ScrollToTopOnTabVisit { state.scrollTo(0) }
    return state
}

/** The same for a lazy list. */
@Composable
fun rememberTabLazyListState(): LazyListState {
    val state = rememberLazyListState()
    ScrollToTopOnTabVisit { state.scrollToItem(0) }
    return state
}
