package mmm.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import mmm.app.ui.exercise.ExerciseScreen
import mmm.app.ui.home.HomeScreen
import mmm.app.ui.progress.ProgressScreen
import mmm.app.ui.settings.SettingsScreen
import mmm.training.ExerciseFamily

private object Routes {
    const val HOME = "home"
    const val EXERCISE = "exercise/{family}"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"

    fun exercise(family: ExerciseFamily) = "exercise/${family.id}"
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
            )
        }
        composable(
            route = Routes.EXERCISE,
            arguments = listOf(navArgument("family") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("family")
            val family = ExerciseFamily.entries.firstOrNull { it.id == id } ?: ExerciseFamily.BAND_ID
            ExerciseScreen(app = app, family = family, onBack = { nav.popBackStack() })
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(app = app, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(app = app, onBack = { nav.popBackStack() })
        }
    }
}
