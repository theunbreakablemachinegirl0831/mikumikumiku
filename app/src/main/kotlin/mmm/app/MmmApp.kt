package mmm.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import mmm.app.ui.audiometry.AudiometryScreen
import mmm.app.ui.earphone.EarphoneScreen
import mmm.app.ui.earphone.EarphoneSearchScreen
import mmm.app.ui.earphone.EarphonesScreen
import mmm.app.ui.exercise.ExerciseScreen
import mmm.app.ui.home.HomeScreen
import mmm.app.ui.live.LiveScreen
import mmm.app.ui.progress.ProgressScreen
import mmm.app.ui.settings.SettingsScreen
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

    fun earphone(id: String) = "earphone/$id"

    fun exercise(family: ExerciseFamily, live: Boolean = false) = "exercise/${family.id}?live=$live"
}

@Composable
fun MmmApp(app: MmmApplication) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onOpenFamily = { nav.navigate(Routes.exercise(it)) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenLive = { nav.navigate(Routes.LIVE) },
                onOpenEarphones = { nav.navigate(Routes.EARPHONES) },
                onOpenAudiometry = { nav.navigate(Routes.AUDIOMETRY) },
            )
        }
        composable(Routes.AUDIOMETRY) {
            AudiometryScreen(app = app, onBack = { nav.popBackStack() })
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
            SettingsScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpenEarphones = { nav.navigate(Routes.EARPHONES) },
            )
        }
        composable(Routes.EARPHONES) {
            EarphonesScreen(
                app = app,
                onBack = { nav.popBackStack() },
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
