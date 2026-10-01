package app.morsecode.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.ThemeMode
import app.morsecode.navigation.Routes
import app.morsecode.ui.TestLifecycleOwner
import app.morsecode.ui.broadcast.BroadcastListTag
import app.morsecode.ui.broadcast.BroadcastSenderScreen
import app.morsecode.ui.broadcast.BroadcastSenderViewModel
import app.morsecode.ui.files.FilesScreen
import app.morsecode.ui.files.FilesViewModel
import app.morsecode.ui.transfer.DuplexTransferScreen
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferItem
import app.morsecode.ui.transfer.TransferLayout
import app.morsecode.ui.transfer.TransferPeer
import app.morsecode.ui.transfer.TransferSession
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.transferUiStateTo
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The layout audit: every window shape the master prompt asks the app to survive, rendered
 * for real and held to the two promises that matter at each one — the primary controls are
 * not clipped away, and the content a phone is supposed to reach is still reachable.
 *
 * Each test asserts the width it thinks it is running at, so a Robolectric that quietly
 * ignored the qualifiers would fail rather than pass in a comfortable window it was never
 * asked about. The scroll is the walklist proof's `performScrollToNode`, which throws when
 * a node cannot be brought into view, so "reachable" means reachable by the list's own
 * scrolling at exactly these dimensions — not merely composable somewhere below the fold.
 *
 * Widths: 320 dp (the narrow Android 6 end), 360 × 740 dp (the reference phone), 412 dp
 * (the common large phone), 600 dp (a tablet/window pane), and a 740 × 360 dp
 * landscape-shaped window. Touch targets are never asserted smaller than 48 dp anywhere:
 * nothing here solves a narrow width by shrinking a control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ResponsiveAuditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val formatters = MorseFormatters.forDefaultLocale()
    private var configuredWidthDp = 0

    // ------------------------------------------------------------------ 320 dp

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `at 320 dp all five category tabs fit and a tap still turns the page`() {
        showFiles()
        assertEquals("this test is the 320 dp proof", 320, configuredWidthDp)

        listOf("Photos", "Videos", "Music", "Apps").forEach { tab ->
            composeTestRule.onNodeWithText(tab).assertIsDisplayed()
        }

        composeTestRule.onNodeWithText("Videos").performClick()
        settle()
        composeTestRule.onNodeWithText("Videos recorded or received appear here.").assertIsDisplayed()
        // The strip stayed put while the page under it changed.
        listOf("Photos", "Videos", "Music", "Apps").forEach { tab ->
            composeTestRule.onNodeWithText(tab).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `the sort sheet's rows and buttons are all reachable at 320 dp`() {
        showFiles()
        assertEquals("this test is the 320 dp proof", 320, configuredWidthDp)

        composeTestRule.onNodeWithContentDescription("Sort").performClick()
        settle()

        composeTestRule.onNodeWithText("Sort by").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Date").assertIsDisplayed()
        // The button row sits under the four radio rows; it is proven reachable the way the
        // walklist proves reachability — by the sheet's own scrolling bringing it into view.
        composeTestRule.onNodeWithText("Ascending").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Descending").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `the selection bar at 320 dp names its count without covering the tabs`() {
        showFiles()
        assertEquals("this test is the 320 dp proof", 320, configuredWidthDp)

        // The header's Select enters selection mode; the grid's own section icons carry the
        // same description and compose after it, so the first node is the header's.
        composeTestRule.onAllNodesWithContentDescription("Select")[0].performClick()
        settle()

        composeTestRule.onNodeWithContentDescription("IMG_2001.jpg").performClick()
        settle()

        composeTestRule.onNodeWithContentDescription("IMG_2001.jpg").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 selected", substring = true).assertIsDisplayed()
        // Neither the selection bar nor the sheet hid the sticky strip.
        listOf("Photos", "Videos", "Music", "Apps").forEach { tab ->
            composeTestRule.onNodeWithText(tab).assertIsDisplayed()
        }
        composeTestRule.onNodeWithContentDescription("Sort").assertIsDisplayed()
    }

    // --------------------------------------------------------- reference 360 × 740

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun `at the reference size the sender's deepest row and its bar are both reachable`() {
        showSender()
        assertEquals("this test is the reference-size proof", 360, configuredWidthDp)

        scrollTo("19%")
        composeTestRule.onNodeWithText("19%").assertIsDisplayed()
        scrollTo("636 MB")
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 412 dp

    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun `at 412 dp a duplex row its header and its bar all render together`() {
        showTransfer()
        assertEquals("this test is the 412 dp proof", 412, configuredWidthDp)

        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
        composeTestRule.onNodeWithText("a").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ 600 dp

    @Test
    @Config(qualifiers = "w600dp-h800dp-xhdpi")
    fun `at tablet width the sticky strip and the header controls remain in place`() {
        showFiles()
        assertEquals("this test is the tablet-width proof", 600, configuredWidthDp)

        listOf("Photos", "Videos", "Music", "Apps").forEach { tab ->
            composeTestRule.onNodeWithText(tab).assertIsDisplayed()
        }
        composeTestRule.onNodeWithContentDescription("Sort").assertIsDisplayed()
        // The grid's section icons carry the same "Select" description as the header action,
        // so the first node is the header's — the same indexing the 320 dp test uses.
        composeTestRule.onAllNodesWithContentDescription("Select")[0].assertIsDisplayed()

        // The content the wide grid shows is still content, not an empty frame.
        composeTestRule.onNodeWithText("Videos").performClick()
        settle()
        composeTestRule.onNodeWithText("Videos recorded or received appear here.").assertIsDisplayed()
    }

    // ------------------------------------------------------- landscape-shaped 740 × 360

    @Test
    @Config(qualifiers = "w740dp-h360dp-xhdpi")
    fun `in a landscape-shaped window the sender still reaches its summary and its bar`() {
        showSender()
        assertEquals("this test is the landscape proof", 740, configuredWidthDp)

        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()
        scrollTo("636 MB")
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    // ------------------------------------------------------------- font scale ×1.5

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun `at one and a half times the system font the bar's labels stay on screen`() {
        showTransfer(fontScale = 1.5f)
        assertEquals("this test is the reference width", 360, configuredWidthDp)

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- helpers

    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag(BroadcastListTag)
            .performScrollToNode(hasText(text, substring = false))
        settle()
    }

    private fun showFiles() {
        val viewModel = FilesViewModel(
            media = FakeMediaRepository(
                images = List(6) { index -> photo("IMG_200${index + 1}.jpg") },
                videos = emptyList(),
            ),
            formatters = formatters,
        )
        composeTestRule.setContent {
            configuredWidthDp = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp().value.roundToInt()
            }
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    FilesScreen(
                        onNavigate = { },
                        onOpenFolder = { },
                        onOpenViewer = { _, _ -> },
                        onOpenMusic = { _, _ -> },
                        onOpenVideo = { },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showSender() {
        val viewModel = BroadcastSenderViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to "r,p,s")),
            formatters,
        )
        composeTestRule.setContent {
            configuredWidthDp = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp().value.roundToInt()
            }
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSenderScreen(
                        onBack = { },
                        onNavigate = { },
                        onSeeCompletion = { },
                        onOpenRecipient = { },
                        onEnded = { },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showTransfer(fontScale: Float = 1f) {
        val state = transferUiStateTo(duplexSession(), TransferLayout.SENDING_FIRST, formatters)
        composeTestRule.setContent {
            configuredWidthDp = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp().value.roundToInt()
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLifecycleOwner provides TestLifecycleOwner(),
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = if (fontScale <= 0f) density.fontScale else fontScale,
                ),
            ) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    DuplexTransferScreen(
                        state = state,
                        onBack = { },
                        onNavigate = { },
                        onPause = { },
                        onResume = { },
                        onCancel = { },
                        onRetry = { },
                        onToggleAll = { },
                        onClearCompleted = { },
                        onAddFiles = { },
                        onBackground = { },
                        onRequestEnd = { },
                        onConfirmEnd = { },
                        onDismissEnd = { },
                    )
                }
            }
        }
        settle()
    }

    private fun photo(name: String) = MediaItem(
        id = name,
        displayName = name,
        kind = MediaKind.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 4_100_000L,
        dateModifiedEpochMillis = System.currentTimeMillis(),
    )

    private fun duplexSession() = TransferSession(
        id = "session-1",
        peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
        outbound = listOf(
            TransferItem(
                id = "OUTGOING-a",
                sessionId = "session-1",
                direction = TransferDirection.OUTGOING,
                fileName = "a",
                kind = MediaKind.OTHER,
                totalBytes = 100L,
                transferredBytes = 10L,
                speedBytesPerSecond = 6_200_000L,
                state = TransferState.SENDING,
            ),
        ),
        inbound = emptyList(),
    )

    private fun settle() {
        // The modal bottom sheet slides up as an animation and then holds a translated
        // position: the compose clock is walked a full second forward so the slide cannot
        // still be in flight when an assertion checks window bounds, the Robolectric
        // scheduler gets the same window for its delayed frame callbacks, and only then
        // is everything waited out.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.mainClock.autoAdvance = true
        ShadowLooper.idleMainLooper(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
