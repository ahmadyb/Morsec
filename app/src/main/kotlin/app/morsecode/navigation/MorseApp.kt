package app.morsecode.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.morsecode.ui.connect.ConnectScreen
import app.morsecode.ui.crashes.CrashesScreen
import app.morsecode.ui.doctor.DoctorScreen
import app.morsecode.ui.files.FilesScreen
import app.morsecode.ui.history.HistoryScreen
import app.morsecode.ui.help.HelpScreen
import app.morsecode.ui.logs.LogsScreen
import app.morsecode.ui.onboarding.OnboardingScreen
import app.morsecode.ui.settings.SettingsScreen

/**
 * The navigation graph.
 *
 * Milestone 1 wires the destinations that exist today. Sending, receiving,
 * broadcast, the WebShare control screen and the media players join the same
 * graph as their engines land — each one behind a feature gate in core-model, so
 * a destination is only reachable when the code behind it really works.
 */
@Composable
public fun MorseApp(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.CONNECT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.CONNECT) {
            ConnectScreen(onNavigate = navController::navigateTopLevel, onOpenHelp = { navController.navigateSimple(Routes.HELP) })
        }

        composable(Routes.FILES) {
            FilesScreen(onNavigate = navController::navigateTopLevel)
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onNavigate = navController::navigateTopLevel)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigate = navController::navigateTopLevel,
                onOpenLogs = { navController.navigateSimple(Routes.LOGS) },
                onOpenCrashes = { navController.navigateSimple(Routes.CRASHES) },
                onOpenDoctor = { navController.navigateSimple(Routes.DOCTOR) },
                onOpenHelp = { navController.navigateSimple(Routes.HELP) },
                onReplayOnboarding = {
                    navController.navigate(Routes.ONBOARDING) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(onBack = navController::back, onNavigate = navController::navigateTopLevel)
        }

        composable(Routes.CRASHES) {
            CrashesScreen(onBack = navController::back, onNavigate = navController::navigateTopLevel)
        }

        composable(Routes.DOCTOR) {
            DoctorScreen(onBack = navController::back, onNavigate = navController::navigateTopLevel)
        }

        composable(Routes.HELP) {
            HelpScreen(
                onBack = navController::back,
                onNavigate = navController::navigateTopLevel,
                onOpenDoctor = { navController.navigateSimple(Routes.DOCTOR) },
            )
        }
    }
}

/** Bottom-bar navigation: single top, state saved, back stack popped to the graph root. */
private fun NavHostController.navigateTopLevel(destination: MorseDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateSimple(route: String) {
    navigate(route) { launchSingleTop = true }
}

private fun NavHostController.back() {
    if (!popBackStack()) {
        navigateTopLevel(MorseDestination.CONNECT)
    }
}
