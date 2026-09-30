package app.morsecode.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import app.morsecode.ui.connect.ConnectScreen
import app.morsecode.ui.crashes.CrashesScreen
import app.morsecode.ui.doctor.DoctorScreen
import app.morsecode.ui.files.FilesScreen
import app.morsecode.ui.music.MusicPlayerScreen
import app.morsecode.ui.folder.FolderScreen
import app.morsecode.ui.history.HistoryScreen
import app.morsecode.ui.help.HelpScreen
import app.morsecode.ui.logs.LogsScreen
import app.morsecode.ui.onboarding.OnboardingScreen
import app.morsecode.ui.settings.SettingsScreen
import app.morsecode.ui.transfer.TransferLayout
import app.morsecode.ui.transfer.TransferScreen
import app.morsecode.ui.video.VideoPlayerScreen
import app.morsecode.ui.viewer.ViewerScreen

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
            ConnectScreen(
                onNavigate = navController::navigateTopLevel,
                onOpenHelp = { navController.navigateSimple(Routes.HELP) },
                onOpenTransfer = { layout ->
                    navController.navigateSimple(Routes.transfer(layout.id))
                },
            )
        }

        composable(Routes.FILES) {
            FilesScreen(
                onNavigate = navController::navigateTopLevel,
                onOpenFolder = { treeUri ->
                    navController.navigateSimple(Routes.folder(Uri.encode(treeUri)))
                },
                onOpenViewer = { item, sort ->
                    navController.navigateSimple(Routes.viewer(Uri.encode(item.id), sort))
                },
                onOpenMusic = { item, sort ->
                    navController.navigateSimple(Routes.music(Uri.encode(item.id), sort))
                },
                onOpenVideo = { item ->
                    navController.navigateSimple(Routes.video(Uri.encode(item.id)))
                },
            )
        }

        // The image viewer: an endless deck of the device's photographs, opened at
        // the one that was tapped, in the order the grid was showing them.
        composable(
            route = Routes.VIEWER,
            arguments = listOf(
                navArgument(Routes.VIEWER_ARG) { type = NavType.StringType },
                navArgument(Routes.VIEWER_SORT_ARG) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            ViewerScreen(onBack = navController::back)
        }

        // The music player: the tapped track inside the queue Files was showing, with
        // the transport, scrubber and queue of §4.6 above the bottom nav.
        composable(
            route = Routes.MUSIC,
            arguments = listOf(
                navArgument(Routes.MUSIC_ARG) { type = NavType.StringType },
                navArgument(Routes.MUSIC_SORT_ARG) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            MusicPlayerScreen(
                onBack = navController::back,
                onNavigate = navController::navigateTopLevel,
            )
        }

        // The video player: the tapped clip on a true-black stage, with the shared
        // scrubber, the ±10 second nudges, volume and mute above nothing else — no
        // bottom nav, because §4.7's screen is immersive the way the viewer's is.
        composable(
            route = Routes.VIDEO,
            arguments = listOf(
                navArgument(Routes.VIDEO_ARG) { type = NavType.StringType },
            ),
        ) {
            VideoPlayerScreen(onBack = navController::back)
        }

        // The duplex transfer session: one destination, two layouts. "Sending + receiving"
        // and "Receiving + sending back" are the same session seen from each end, so which
        // one is drawn comes from the route rather than from a second copy of the screen.
        composable(
            route = Routes.TRANSFER,
            arguments = listOf(
                navArgument(Routes.TRANSFER_ARG) { type = NavType.StringType },
            ),
        ) {
            TransferScreen(
                onBack = navController::back,
                onNavigate = navController::navigateTopLevel,
                // Ending a session leaves it: the reference returns to Connect, and the
                // session it just closed is not somewhere to go back to.
                onEnded = {
                    navController.navigate(Routes.CONNECT) {
                        popUpTo(Routes.TRANSFER) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // The internal folder browser. One entry holds the whole walk: the browser
        // changes level in place and turns the back gesture into "up one level"
        // until the granted folder is reached, so nothing is stacked per level.
        composable(
            route = Routes.FOLDER,
            arguments = listOf(
                navArgument(Routes.FOLDER_ARG) { type = NavType.StringType },
            ),
        ) {
            FolderScreen(
                onBack = navController::back,
                onNavigate = navController::navigateTopLevel,
            )
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
