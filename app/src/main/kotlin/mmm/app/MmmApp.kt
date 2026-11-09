package mmm.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import mmm.app.player.NowPlaying
import mmm.app.ui.audiometry.AudiometryScreen
import mmm.app.ui.earphone.EarphoneScreen
import mmm.app.ui.earphone.EarphoneSearchScreen
import mmm.app.ui.earphone.EarphonesScreen
import mmm.app.ui.exercise.ExerciseScreen
import mmm.app.ui.home.HomeScreen
import mmm.app.ui.live.LiveScreen
import mmm.app.ui.player.Artwork
import mmm.app.ui.player.PlayerScreen
import mmm.app.ui.progress.ProgressScreen
import mmm.app.ui.settings.SettingsScreen
import mmm.app.ui.theme.Backdrop
import mmm.app.ui.theme.DefaultTint
import mmm.app.ui.theme.glass
import mmm.training.ExerciseFamily

private object Routes {
    const val HOME = "home"
    const val EXERCISE = "exercise/{family}?live={live}"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val LIVE = "live"
    const val EARPHONES = "earphones"
    const val EARPHONE = "earphone/{id}"
    const val EARPHONE_SEARCH = "earphone-search"
    const val AUDIOMETRY = "audiometry"
    const val PLAYER = "player"

    fun earphone(id: String) = "earphone/$id"

    fun exercise(family: ExerciseFamily, live: Boolean = false) = "exercise/${family.id}?live=$live"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** The tabs of the bottom panel, the reference's icon row: everything else opens on top of them. */
private val TABS = listOf(
    Tab(Routes.HOME, "훈련", Icons.Rounded.School),
    Tab(Routes.PLAYER, "플레이어", Icons.Rounded.MusicNote),
    Tab(Routes.AUDIOMETRY, "청력", Icons.Rounded.Hearing),
    Tab(Routes.EARPHONES, "이어폰", Icons.Rounded.Headphones),
    Tab(Routes.SETTINGS, "설정", Icons.Rounded.Settings),
)
private val TAB_ROUTES = TABS.map { it.route }.toSet()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabIndexDelta(): Int {
    val from = TABS.indexOfFirst { it.route == initialState.destination.route }
    val to = TABS.indexOfFirst { it.route == targetState.destination.route }
    return to - from
}

/**
 * Tabs fade through with a slight drift towards the side they came from; screens opened from a
 * tab slide in from the right over a fade and slide back out when closed - the movement says
 * whether the listener went sideways or deeper.
 */
private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabSwitch()) {
        val direction = if (tabIndexDelta() >= 0) 1 else -1
        fadeIn(tween(260, delayMillis = 60)) +
            slideInHorizontally(tween(320)) { direction * it / 12 } +
            scaleIn(tween(320), initialScale = 0.98f)
    } else {
        slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(260))
    }
}

private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(340)) { -it / 8 } + fadeOut(tween(200))
}

private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabSwitch()) fadeIn(tween(260)) else slideInHorizontally(tween(340)) { -it / 8 } + fadeIn(tween(260))
}

private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(340)) { it / 3 } + fadeOut(tween(200))
}

@Composable
fun MmmApp(app: MmmApplication) {
    val nav = rememberNavController()
    val now by app.player.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = route in TAB_ROUTES

    Backdrop(tint = now.tint ?: DefaultTint) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.weight(1f)) {
                AppNavHost(app, nav)
            }
            AnimatedVisibility(
                visible = onTab,
                enter = slideInVertically(tween(300)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(160)),
            ) {
                BottomPanel(
                    now = now,
                    route = route,
                    showMiniPlayer = now.hasMedia && route != Routes.PLAYER,
                    onTab = { target -> nav.goToTab(target) },
                    onToggle = app.player::toggle,
                    onOpenPlayer = { nav.goToTab(Routes.PLAYER) },
                )
            }
        }
    }
}

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The reference's floating glass panel: the mini player (when something is loaded and the player
 * itself is not on screen) over a thin progress line, and the tab icons.
 */
@Composable
private fun BottomPanel(
    now: NowPlaying,
    route: String?,
    showMiniPlayer: Boolean,
    onTab: (String) -> Unit,
    onToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(30.dp), strong = true),
    ) {
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = expandVertically(tween(280)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(240)) + fadeOut(tween(160)),
        ) {
            Column(Modifier.clickable(onClick = onOpenPlayer).padding(start = 12.dp, end = 8.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(trackId = now.trackId, size = 48.dp, corner = 10.dp, bitmap = now.artwork)
                    Column(Modifier.weight(1f)) {
                        Text(
                            now.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(now.artist, now.album).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (now.isPlaying) "일시정지" else "재생",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 4.dp).height(3.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TABS.forEach { tab ->
                val selected = route == tab.route
                val tint by animateColorAsState(
                    if (selected) Color.White else Color.White.copy(alpha = 0.42f),
                    animationSpec = tween(220),
                    label = "tab",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTab(tab.route) },
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(26.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(app: MmmApplication, nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onOpenFamily = { nav.navigate(Routes.exercise(it)) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                onOpenLive = { nav.navigate(Routes.LIVE) },
                onOpenEarphones = { nav.goToTab(Routes.EARPHONES) },
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(app = app, onOpenEarphones = { nav.goToTab(Routes.EARPHONES) })
        }
        composable(Routes.AUDIOMETRY) {
            AudiometryScreen(app = app, onBack = null)
        }
        composable(
            route = Routes.EXERCISE,
            arguments = listOf(
                navArgument("family") { type = NavType.StringType },
                navArgument("live") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("family")
            val family = ExerciseFamily.entries.firstOrNull { it.id == id } ?: ExerciseFamily.BAND_ID
            val live = entry.arguments?.getBoolean("live") ?: false
            ExerciseScreen(app = app, family = family, live = live, onBack = { nav.popBackStack() })
        }
        composable(Routes.LIVE) {
            LiveScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpenFamily = { nav.navigate(Routes.exercise(it, live = true)) },
            )
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(app = app, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(app = app, onBack = null, onOpenEarphones = { nav.goToTab(Routes.EARPHONES) })
        }
        composable(Routes.EARPHONES) {
            EarphonesScreen(
                app = app,
                onBack = null,
                onOpen = { nav.navigate(Routes.earphone(it)) },
                onSearch = { nav.navigate(Routes.EARPHONE_SEARCH) },
            )
        }
        composable(Routes.EARPHONE_SEARCH) {
            EarphoneSearchScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onAdded = { id ->
                    nav.navigate(Routes.earphone(id)) {
                        popUpTo(Routes.EARPHONE_SEARCH) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.EARPHONE,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            EarphoneScreen(app = app, id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
