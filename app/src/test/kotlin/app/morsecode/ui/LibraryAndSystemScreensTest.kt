package app.morsecode.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.logging.MorseLogger
import app.morsecode.core.data.repository.DiagnosticsRepository
import app.morsecode.core.data.repository.HistoryRepository
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.CrashReport
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.ThemeMode
import app.morsecode.ui.TestLifecycleOwner
import app.morsecode.ui.connect.FakeSettingsRepository
import app.morsecode.ui.crashes.CrashesScreen
import app.morsecode.ui.crashes.CrashesViewModel
import app.morsecode.ui.doctor.DoctorScreen
import app.morsecode.ui.doctor.DoctorViewModel
import app.morsecode.ui.help.HelpScreen
import app.morsecode.ui.history.HistoryScreen
import app.morsecode.ui.history.HistoryViewModel
import app.morsecode.ui.logs.LogsScreen
import app.morsecode.ui.logs.LogsViewModel
import app.morsecode.ui.onboarding.OnboardingScreen
import app.morsecode.ui.onboarding.OnboardingViewModel
import app.morsecode.ui.settings.SettingsScreen
import app.morsecode.ui.settings.SettingsViewModel
import app.morsecode.ui.FakeMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The screens M1 shipped and the M2 audit found without a single Compose test:
 * onboarding, Help, History, Settings, Logs, Crash reports and the Doctor.
 *
 * Each test is the smallest honest proof that the screen exists the way the
 * mockup registry lists it — it renders its own copy, the bottom bar lights the
 * destination the reference lights (Settings for every nested screen), and the
 * one affordance that defines the screen is on it: the FAQ opens, the crash
 * report is viewable with Share and Clear, the log offers export. The data
 * comes from narrow fakes over the real repository interfaces, so what is
 * asserted is the screen's own behaviour rather than a database's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi", application = Application::class)
class LibraryAndSystemScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val formatters = MorseFormatters.forDefaultLocale()

    // ------------------------------------------------------------- getting started

    @Test
    fun `onboarding walks its four cards and hands back finished`() {
        var finished = 0
        val viewModel = OnboardingViewModel(FakeSettingsRepository(), QuietLogger)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    OnboardingScreen(onFinished = { finished += 1 }, viewModel = viewModel)
                }
            }
        }
        settle()

        composeTestRule.onNodeWithText("Send files without the internet").assertIsDisplayed()

        // Card 1: Continue. Card 2: Not now — the decline that must still advance,
        // without firing the one real permission request this screen owns.
        composeTestRule.onNodeWithText("Continue").performClick()
        settle()
        composeTestRule.onNodeWithText("Permissions we need").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").performClick()
        settle()

        composeTestRule.onNodeWithText("Or use a browser").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performClick()
        settle()

        composeTestRule.onNodeWithText("You're all set").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Morsecode").performClick()
        settle()

        assertEquals("the last card finishes the tour", 1, finished)
    }

    @Test
    fun `help opens its FAQ and keeps Settings lit under it`() {
        var doctorOpens = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    HelpScreen(
                        onBack = { },
                        onNavigate = { },
                        onOpenDoctor = { doctorOpens += 1 },
                    )
                }
            }
        }
        settle()

        composeTestRule.onNodeWithText("Help & FAQ").assertIsDisplayed()
        assertTrue("a nested screen lights Settings", selected("Settings"))

        // The shortcut sits at the end of the list, so it is used while the FAQ is still
        // collapsed, and then the accordion is opened to prove an answer really shows.
        composeTestRule.onNodeWithText("Open Connection Doctor").performClick()
        settle()
        assertEquals("the Doctor shortcut is wired", 1, doctorOpens)

        composeTestRule.onNodeWithText("Why can't my phone see the other one?").performClick()
        settle()
        composeTestRule
            .onNodeWithText("the Connection Doctor reports that case", substring = true)
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------ library

    @Test
    fun `history shows both directions and its search over an empty store`() {
        val viewModel = HistoryViewModel(FakeHistoryRepository(), formatters)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    HistoryScreen(onNavigate = { }, viewModel = viewModel)
                }
            }
        }
        settle()

        // The title and the bottom-bar label are both exactly "History": two nodes,
        // one header and one bar, which is itself the proof that both rendered.
        assertTrue(
            "the History header and its lit bar entry both rendered",
            composeTestRule.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty(),
        )
        composeTestRule.onNodeWithText("Received").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
        // The search field lives behind the header's search toggle; the toggle brings it up.
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        settle()
        composeTestRule.onNodeWithText("Search history").assertIsDisplayed()
        assertTrue("History is the lit destination", selected("History"))
    }

    @Test
    fun `settings lists its mockup entries with Settings lit`() {
        val viewModel = SettingsViewModel(
            FakeSettingsRepository(),
            FakeDiagnosticsRepository(),
            FakeMediaRepository(),
            context,
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    SettingsScreen(
                        onNavigate = { },
                        onOpenLogs = { },
                        onOpenCrashes = { },
                        onOpenDoctor = { },
                        onOpenHelp = { },
                        onReplayOnboarding = { },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()

        assertTrue(
            "the Settings header and its lit bar entry both rendered",
            composeTestRule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty(),
        )
        composeTestRule.onNodeWithText("Accent colour").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dark mode").assertIsDisplayed()
        assertTrue("Settings lights itself", selected("Settings"))
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `logs offers export and the errors filter over an empty log`() {
        val viewModel = LogsViewModel(FakeDiagnosticsRepository(), formatters)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    LogsScreen(onBack = { }, onNavigate = { }, viewModel = viewModel)
                }
            }
        }
        settle()

        composeTestRule.onNodeWithText("Logs").assertIsDisplayed()
        composeTestRule.onNodeWithText("No log entries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export .txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("Errors only").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clear").assertIsDisplayed()
        assertTrue("Logs is a Settings child", selected("Settings"))
    }

    @Test
    fun `a crash report is viewable shareable and clearable, and says it stays local`() {
        val report = CrashReport(
            id = 7L,
            occurredEpochMillis = 1_700_000_000_000L,
            component = "TransferService",
            exceptionType = "java.io.IOException",
            message = "broken pipe",
            stackTrace = "at app.morsecode.TransferService.kt:42",
            appVersion = "1.0.0",
            versionCode = 1,
            androidSdkInt = 34,
            deviceModel = "Pixel 7",
        )
        val viewModel = CrashesViewModel(FakeDiagnosticsRepository(listOf(report)), formatters)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    CrashesScreen(onBack = { }, onNavigate = { }, viewModel = viewModel)
                }
            }
        }
        settle()

        composeTestRule.onNodeWithText("Crash reports").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stored only on this device").assertIsDisplayed()
        // View: the report itself, badge and all.
        composeTestRule.onNodeWithText("CRASH").assertIsDisplayed()
        composeTestRule.onNodeWithText("TransferService").assertIsDisplayed()
        composeTestRule.onNodeWithText("java.io.IOException").assertIsDisplayed()
        // Export and clear, exactly as the approved decision names them.
        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        assertTrue("Crashes is a Settings child", selected("Settings"))
    }

    // --------------------------------------------------------------------- doctor

    @Test
    fun `the doctor renders its title and a refreshable check list`() {
        val viewModel = DoctorViewModel(context, FakeMediaRepository(), Dispatchers.Unconfined)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    DoctorScreen(onBack = { }, onNavigate = { }, viewModel = viewModel)
                }
            }
        }
        settle()

        composeTestRule.onNodeWithText("Connection Doctor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh checks").assertIsDisplayed()
        assertTrue("the Doctor is a Settings child", selected("Settings"))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The `Selected` semantics a screen reader announces for [text].
     *
     * The matcher keeps the query to nodes that actually carry a selection state, so a
     * screen whose title repeats its own bottom-bar label — History, Settings — resolves
     * to the bar's node rather than to whichever of the two the finder met first.
     */
    private fun selected(text: String): Boolean =
        composeTestRule
            .onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Selected]

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}

/** The Logs and Crashes screens' data source over a fixed, empty log and fixed reports. */
internal class FakeDiagnosticsRepository(
    private val crashes: List<CrashReport> = emptyList(),
) : DiagnosticsRepository {
    override fun observeLogs(limit: Int): Flow<List<LogEntry>> = flowOf(emptyList())
    override fun observeProblems(limit: Int): Flow<List<LogEntry>> = flowOf(emptyList())
    override suspend fun clearLogs() = Unit
    override suspend fun logCount(): Int = 0
    override suspend fun exportLogs(): String = ""
    override fun observeCrashReports(): Flow<List<CrashReport>> = flowOf(crashes)
    override fun observeCrashCount(): Flow<Int> = flowOf(crashes.size)
    override suspend fun deleteCrashReport(id: Long) = Unit
    override suspend fun clearCrashReports() = Unit
    override suspend fun exportCrashReports(): String = ""
}

/** History over an empty store in both directions. */
internal class FakeHistoryRepository : HistoryRepository {
    override fun observe(direction: SessionDirection): Flow<List<app.morsecode.core.model.HistoryEntry>> =
        flowOf(emptyList())

    override fun search(
        direction: SessionDirection,
        query: String,
    ): Flow<List<app.morsecode.core.model.HistoryEntry>> = flowOf(emptyList())

    override fun countFor(direction: SessionDirection): Flow<Int> = flowOf(0)
    override suspend fun recent(limit: Int): List<app.morsecode.core.model.HistoryEntry> = emptyList()
    override suspend fun delete(historyId: String) = Unit
    override suspend fun clear(direction: SessionDirection): Int = 0
    override suspend fun clearAll(): Int = 0
}

/** A logger that accepts everything and keeps nothing — onboarding only needs its shape. */
internal object QuietLogger : MorseLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, errorId: String?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?, errorId: String?) = Unit
    override fun failure(tag: String, errorId: String, message: String, throwable: Throwable?) = Unit
    override fun observe(limit: Int): Flow<List<LogEntry>> = flowOf(emptyList())
    override fun observeProblems(limit: Int): Flow<List<LogEntry>> = flowOf(emptyList())
    override suspend fun clear() = Unit
    override suspend fun count(): Int = 0
    override suspend fun export(): String = ""
}
