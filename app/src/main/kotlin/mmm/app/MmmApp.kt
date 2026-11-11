package mmm.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import mmm.app.player.NowPlaying
import mmm.app.ui.audiometry.AudiometryScreen
import mmm.app.ui.earphone.EarphoneScreen
import mmm.app.ui.earphone.EarphoneSearchScreen
import mmm.app.ui.earphone.EarphonesScreen
import mmm.app.ui.eq.EqScreen
import mmm.app.ui.exercise.ExerciseScreen
import mmm.app.ui.hub.HearingCorrectionScreen
import mmm.app.ui.hub.HubScreen
import mmm.app.ui.hub.TrainingSetupScreen
import mmm.app.ui.live.LiveScreen
import mmm.app.ui.player.Artwork
import mmm.app.ui.player.LibraryScreen
import mmm.app.ui.player.NowPlayingScreen
import mmm.app.ui.progress.ProgressScreen
import mmm.app.ui.settings.SettingsScreen
import mmm.app.ui.spotify.SpotifyScreen
import mmm.app.ui.components.glass.Backdrop
import mmm.app.ui.components.glass.GlassIconButton
import mmm.app.ui.components.glass.GlassLevel
import mmm.app.ui.components.glass.GlassNavItem
import mmm.app.ui.components.glass.GlassNavigationBar
import mmm.app.ui.components.glass.GlassProgressBar
import mmm.app.ui.components.glass.LocalBackdropTint
import mmm.app.ui.components.glass.LocalGlassBackdrop
import mmm.app.ui.components.glass.glass
import mmm.app.ui.components.glass.glassBackdropSource
import mmm.app.ui.components.glass.rememberGlassBackdrop
import mmm.app.ui.theme.DefaultTint
import mmm.app.ui.theme.GlassDimensions
import mmm.app.ui.theme.GlassMotion
import mmm.app.ui.theme.GlassTheme
import mmm.app.ui.theme.GlassShapes
import mmm.app.ui.components.glass.LocalPanelInset
import mmm.training.ExerciseFamily
import kotlin.math.roundToInt

private object Routes {
    const val HUB = "hub"
    const val PLAYER = "player"
    const val EQ = "eq"
    const val SPOTIFY = "spotify"
    const val SETTINGS = "settings"

    const val TRAINING = "training/{family}"
    const val EXERCISE = "exercise/{family}?live={live}"
    const val PROGRESS = "progress"
    const val LIVE = "live"
    const val AUDIOMETRY = "audiometry"
    const val HEARING = "hearing"
    const val EARPHONES = "earphones"
    const val EARPHONE = "earphone/{id}"
    const val EARPHONE_SEARCH = "earphone-search?pick={pick}"

    fun training(family: ExerciseFamily) = "training/${family.id}"

    fun exercise(family: ExerciseFamily, live: Boolean = false) = "exercise/${family.id}?live=$live"

    fun earphone(id: String) = "earphone/$id"

    /** @param pick return to the caller once an earphone is added, instead of opening its page */
    fun earphoneSearch(pick: Boolean) = "earphone-search?pick=$pick"
}

private class Tab(val route: String, val label: String, val icon: ImageVector)

/** The bottom panel's icons, in the reference's order of use: choose, play, shape, stream, set up. */
private val TABS = listOf(
    Tab(Routes.HUB, "검사 · 훈련", Icons.Rounded.GridView),
    Tab(Routes.PLAYER, "플레이어", Icons.Rounded.QueueMusic),
    Tab(Routes.EQ, "재생 EQ", Icons.Rounded.GraphicEq),
    Tab(Routes.SPOTIFY, "Spotify", Icons.Rounded.Sensors),
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
 * Tabs give way to each other like panes of the same glass: the old one sinks back and fades as
 * the new one comes forward from the side it was on. Screens opened from a tab slide in from the
 * right over a fade and back out when closed - the movement says whether the listener went
 * sideways or deeper.
 */
private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabSwitch()) {
        val direction = if (tabIndexDelta() >= 0) 1 else -1
        fadeIn(tween(280, delayMillis = 50)) +
            slideInHorizontally(spring(dampingRatio = 0.82f, stiffness = 320f)) { direction * it / 9 } +
            scaleIn(spring(dampingRatio = 0.82f, stiffness = 320f), initialScale = 0.96f)
    } else {
        slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 340f)) { it / 3 } + fadeIn(tween(260))
    }
}

private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabSwitch()) {
        fadeOut(tween(140)) + scaleOut(tween(200), targetScale = 0.97f)
    } else {
        slideOutHorizontally(tween(320)) { -it / 8 } + fadeOut(tween(200)) + scaleOut(tween(320), targetScale = 0.96f)
    }
}

private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabSwitch()) {
        fadeIn(tween(260))
    } else {
        slideInHorizontally(tween(320)) { -it / 8 } + fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.96f)
    }
}

private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200))
}

/**
 * The shell: the backdrop, the screens, and floating over them the glass panel with the mini
 * player and the tabs.
 *
 * The now-playing screen is not a destination but a state of the panel. Tapping or pulling up
 * the mini player opens it, and one number - how far open, 0 to 1 - drives everything at once:
 * the screen fades up, the mini player folds away, and the artwork flies from one to the other,
 * so it reads as the same thing growing rather than one thing replacing another. The number
 * follows the finger while dragging and a spring when let go.
 */
@Composable
fun MmmApp(app: MmmApplication) {
    val nav = rememberNavController()
    val now by app.player.state.collectAsStateWithLifecycle()
    val external by app.external.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = route in TAB_ROUTES
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // The upper bound leaves room for the spring to run a little past open, which is the bounce.
    val expand = remember { Animatable(0f).apply { updateBounds(0f, 1.08f) } }
    val open by remember { derivedStateOf { expand.value > OPEN_THRESHOLD } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var areaHeight by remember { mutableIntStateOf(1) }
    var miniArt by remember { mutableStateOf(Rect.Zero) }
    var fullArt by remember { mutableStateOf(Rect.Zero) }
    var panelHeight by remember { mutableIntStateOf(0) }
    var iconRowHeight by remember { mutableIntStateOf(0) }

    fun settle(target: Float, velocity: Float = 0f) {
        scope.launch {
            expand.animateTo(target, spring(dampingRatio = if (target > 0.5f) 0.8f else 1f, stiffness = 280f), initialVelocity = velocity)
        }
    }
    val drag = rememberDraggableState { delta ->
        scope.launch { expand.snapTo((expand.value - delta / areaHeight).coerceIn(0f, 1f)) }
    }
    val onDragStopped: (Float) -> Unit = { velocity ->
        // A flick decides by its direction; a slow drag by which half it was let go in.
        val closing = velocity > FLICK || (velocity > -FLICK && expand.value < 0.5f)
        settle(if (closing) 0f else 1f, -velocity / areaHeight)
    }

    BackHandler(enabled = open) { settle(0f) }
    LaunchedEffect(now.hasMedia) { if (!now.hasMedia) expand.snapTo(0f) }

    val tint = (if (route == Routes.SPOTIFY && !open) external.tint else null) ?: now.tint ?: DefaultTint
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    // What the floating panel sees through and blurs: the backdrop and every screen on it. The
    // panel itself is drawn beside this, not inside it, or it would be looking at itself.
    val backdrop = rememberGlassBackdrop(settings.backdropBlur)

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalBackdropTint provides tint) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                Backdrop(tint = tint) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .onGloballyPositioned {
                                origin = it.positionInRoot()
                                areaHeight = it.size.height.coerceAtLeast(1)
                            },
                    ) {
                        val inset = if (onTab) with(density) { panelHeight.toDp() } + 12.dp else 0.dp
                        CompositionLocalProvider(LocalPanelInset provides inset) {
                            AppNavHost(app, nav)
                        }

                        if (open) {
                            // The same backdrop again, opaque, so the tab underneath does not show through.
                            // It also takes the touches, so a tap between controls does not reach the list below.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = (expand.value * 1.8f).coerceIn(0f, 1f) }
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                Backdrop(tint = now.tint ?: DefaultTint) {}
                            }
                            NowPlayingScreen(
                                app = app,
                                now = now,
                                artAlpha = { if (expand.value >= SETTLED) 1f else 0f },
                                onArtBounds = { fullArt = it },
                                onOpenEq = {
                                    settle(0f)
                                    nav.goToTab(Routes.EQ)
                                },
                                modifier = Modifier
                                    .padding(bottom = with(density) { iconRowHeight.toDp() } + PANEL_MARGIN * 2)
                                    .graphicsLayer {
                                        val p = expand.value
                                        alpha = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
                                        translationY = (1f - p.coerceAtMost(1f)) * 56.dp.toPx()
                                    }
                                    .draggable(drag, Orientation.Vertical, onDragStopped = { onDragStopped(it) }),
                            )
                        }

                    }
                }
            }

            // Chrome: above the backdrop source, inside the same insets.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                AnimatedVisibility(
                    visible = onTab || open,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(160)),
                ) {
                    BottomPanel(
                        now = now,
                        selected = TABS.indexOfFirst { it.route == if (open) Routes.PLAYER else route },
                        progress = { expand.value },
                        onTab = { target ->
                            settle(0f)
                            nav.goToTab(target)
                        },
                        onToggle = app.player::toggle,
                        onOpenPlayer = { settle(1f) },
                        onMiniArt = { miniArt = it },
                        onIconRowHeight = { iconRowHeight = it },
                        miniModifier = Modifier.draggable(drag, Orientation.Vertical, onDragStopped = { onDragStopped(it) }),
                        modifier = Modifier.onSizeChanged { panelHeight = it.height },
                    )
                }

                // The artwork in flight: one picture, drawn at the full size and scaled down towards the
                // mini player, so it is the same pixels all the way and nothing pops.
                if (open && fullArt.width > 0f && miniArt.width > 0f) {
                    Artwork(
                        trackId = now.trackId,
                        size = with(density) { fullArt.width.toDp() },
                        corner = 28.dp,
                        bitmap = now.artwork,
                        modifier = Modifier.graphicsLayer {
                            val p = expand.value
                            val scale = (miniArt.width + (fullArt.width - miniArt.width) * p) / fullArt.width
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = miniArt.left + (fullArt.left - miniArt.left) * p - origin.x
                            translationY = miniArt.top + (fullArt.top - miniArt.top) * p - origin.y
                            alpha = if (p >= SETTLED) 0f else 1f
                        },
                    )
                }
            }
        }
    }
}

private const val OPEN_THRESHOLD = 0.002f
private const val SETTLED = 0.998f
private const val FLICK = 900f
private val PANEL_MARGIN = 8.dp

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The reference's floating panel: the mini player with its progress line, over the destinations.
 * It is the one pane in the app that floats over moving content, so it is the one that blurs what
 * is behind it. The mini player's height follows [progress], folding away as the now-playing
 * screen opens.
 */
@Composable
private fun BottomPanel(
    now: NowPlaying,
    selected: Int,
    progress: () -> Float,
    onTab: (String) -> Unit,
    onToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onMiniArt: (Rect) -> Unit,
    onIconRowHeight: (Int) -> Unit,
    miniModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val reduced = GlassTheme.reducedMotion
    val mini by animateFloatAsState(
        if (now.hasMedia) 1f else 0f,
        GlassMotion.unlessReduced(reduced, GlassMotion.panel()),
        label = "mini",
    )

    Column(
        modifier
            .padding(horizontal = GlassDimensions.panelMargin, vertical = PANEL_MARGIN)
            .fillMaxWidth()
            .glass(GlassShapes.floating, level = GlassLevel.Regular, floating = true),
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val shown = (mini * (1f - progress())).coerceIn(0f, 1f)
                    layout(placeable.width, (placeable.height * shown).roundToInt()) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .graphicsLayer { alpha = (mini * (1f - progress() * 2.5f)).coerceIn(0f, 1f) },
        ) {
            Column(
                miniModifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "재생 화면 열기",
                        onClick = onOpenPlayer,
                    )
                    .padding(start = 12.dp, end = 8.dp, top = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(
                        trackId = now.trackId,
                        size = 48.dp,
                        corner = 10.dp,
                        bitmap = now.artwork,
                        modifier = Modifier
                            .onGloballyPositioned { onMiniArt(it.boundsInRoot()) }
                            .graphicsLayer { alpha = if (progress() > OPEN_THRESHOLD) 0f else 1f },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(now.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(now.artist, now.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlassIconButton(onClick = onToggle, level = null) {
                        Icon(
                            if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (now.isPlaying) "일시정지" else "재생",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                GlassProgressBar(
                    progress = { if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 4.dp).height(3.dp),
                )
            }
        }
        GlassNavigationBar(
            items = NAV_ITEMS,
            selected = selected,
            onSelect = { onTab(TABS[it].route) },
            modifier = Modifier.onSizeChanged { onIconRowHeight(it.height) },
        )
    }
}

private val NAV_ITEMS = TABS.map { GlassNavItem(it.label, it.icon) }

@Composable
private fun AppNavHost(app: MmmApplication, nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.HUB,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable(Routes.HUB) {
            HubScreen(
                app = app,
                onOpenAudiometry = { nav.navigate(Routes.AUDIOMETRY) },
                onOpenEarphones = { nav.navigate(Routes.EARPHONES) },
                onOpenHearing = { nav.navigate(Routes.HEARING) },
                onOpenTraining = { nav.navigate(Routes.training(it)) },
                onOpenLive = { nav.navigate(Routes.LIVE) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
            )
        }
        composable(Routes.PLAYER) { LibraryScreen(app) }
        composable(Routes.EQ) {
            EqScreen(
                app = app,
                onOpenEarphones = { nav.navigate(Routes.EARPHONES) },
                onFindEarphone = { nav.navigate(Routes.earphoneSearch(pick = true)) },
            )
        }
        composable(Routes.SPOTIFY) { SpotifyScreen(app, onOpenEq = { nav.goToTab(Routes.EQ) }) }
        composable(Routes.SETTINGS) { SettingsScreen(app, onOpenEarphones = { nav.navigate(Routes.EARPHONES) }) }

        composable(
            route = Routes.TRAINING,
            arguments = listOf(navArgument("family") { type = NavType.StringType }),
        ) { entry ->
            val family = familyOf(entry.arguments?.getString("family"))
            TrainingSetupScreen(
                app = app,
                family = family,
                onBack = { nav.popBackStack() },
                onStart = { nav.navigate(Routes.exercise(family)) },
            )
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
            val live = entry.arguments?.getBoolean("live") ?: false
            ExerciseScreen(app = app, family = familyOf(entry.arguments?.getString("family")), live = live, onBack = { nav.popBackStack() })
        }
        composable(Routes.LIVE) {
            LiveScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpenFamily = { nav.navigate(Routes.exercise(it, live = true)) },
            )
        }
        composable(Routes.PROGRESS) { ProgressScreen(app = app, onBack = { nav.popBackStack() }) }
        composable(Routes.AUDIOMETRY) { AudiometryScreen(app = app, onBack = { nav.popBackStack() }) }
        composable(Routes.HEARING) {
            HearingCorrectionScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpenAudiometry = { nav.navigate(Routes.AUDIOMETRY) },
            )
        }
        composable(Routes.EARPHONES) {
            EarphonesScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.earphone(it)) },
                onSearch = { nav.navigate(Routes.earphoneSearch(pick = false)) },
            )
        }
        composable(
            route = Routes.EARPHONE_SEARCH,
            arguments = listOf(
                navArgument("pick") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val pick = entry.arguments?.getBoolean("pick") ?: false
            EarphoneSearchScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onAdded = { id ->
                    if (pick) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.earphone(id)) {
                            popUpTo(Routes.EARPHONE_SEARCH) { inclusive = true }
                        }
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

private fun familyOf(id: String?): ExerciseFamily = ExerciseFamily.entries.firstOrNull { it.id == id } ?: ExerciseFamily.BAND_ID
