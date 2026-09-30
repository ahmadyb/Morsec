package app.morsecode.ui.broadcast

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.ThemeMode
import app.morsecode.navigation.Routes
import app.morsecode.ui.TestLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The broadcast screens at the reference phone's width: 360 dp, and short.
 *
 * Every other screen test runs in a tall viewport, where more of a list happens to fit. That is
 * a convenient place to assert about rows, but it is not a phone, and the one thing it cannot
 * show is that the whole screen is reachable on a phone — the bottom of a `LazyColumn` and the
 * action bar under it both live off-screen until something scrolls.
 *
 * So this file is the reachability proof, and it is a proof rather than an assumption because
 * of how it scrolls: `performScrollToNode` walks the list's own scroll action and throws when
 * the node cannot be brought into view. A row that had been squeezed out of the layout by a
 * narrower screen, or a bar that the list had been allowed to cover, would fail here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", application = Application::class)
class BroadcastReachabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val formatters = MorseFormatters.forDefaultLocale()
    private var windowWidthDp = 0

    @Test
    fun `the picker's last phone and its start action are reachable at 360 dp`() {
        showPicker()

        assertEquals("this test is the 360 dp proof", 360, windowWidthDp)

        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()

        scrollTo("Connect & broadcast to (0)")
        composeTestRule.onNodeWithText("Connect & broadcast to (0)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect & broadcast to (0)").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Pick at least two phones to broadcast — 2 more.")
            .assertIsDisplayed()
    }

    @Test
    fun `every file, its last phone's row and the bar are reachable at 360 dp`() {
        showSender()

        assertEquals("this test is the 360 dp proof", 360, windowWidthDp)

        listOf("holiday_2019.mp4", "IMG_2043.jpg", "live_set_final.flac").forEach { file ->
            scrollTo(file)
            composeTestRule.onNodeWithText(file).assertIsDisplayed()
        }

        // The third phone under the last file — a nested row inside the last block of the list.
        scrollTo("19%")
        composeTestRule.onNodeWithText("19%").assertIsDisplayed()

        // And the batch summary, which is the last thing in the list.
        scrollTo("636 MB")
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()

        // The bar is not part of the list, so nothing scrolled it away.
        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    @Test
    fun `a receiver's last file and its summary are reachable at 360 dp`() {
        showReceiver("s")

        assertEquals("this test is the 360 dp proof", 360, windowWidthDp)

        scrollTo("live_set_final.flac")
        composeTestRule.onNodeWithText("live_set_final.flac").assertIsDisplayed()

        scrollTo("1 paused · 1 failed")
        composeTestRule.onNodeWithText("1 paused · 1 failed").assertIsDisplayed()

        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    @Test
    fun `a completion screen's last phone and the report under it are reachable at 360 dp`() {
        showSent()

        assertEquals("this test is the 360 dp proof", 360, windowWidthDp)

        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()

        scrollTo("9 delivered")
        composeTestRule.onNodeWithText("9 delivered").assertIsDisplayed()
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- helpers

    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag(BroadcastListTag)
            .performScrollToNode(hasText(text, substring = false))
        settle()
    }

    private fun showPicker() {
        composeTestRule.setContent {
            windowWidthDp = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastPickScreen(
                        onBack = { },
                        onNavigate = { },
                        onBroadcastTo = { },
                        viewModel = BroadcastPickerViewModel(SavedStateHandle(), formatters),
                    )
                }
            }
        }
        settle()
    }

    private fun showSender() {
        composeTestRule.setContent {
            windowWidthDp = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSenderScreen(
                        onBack = { },
                        onNavigate = { },
                        onSeeCompletion = { },
                        onOpenRecipient = { },
                        onEnded = { },
                        viewModel = BroadcastSenderViewModel(
                            SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to "r,p,s")),
                            formatters,
                        ),
                    )
                }
            }
        }
        settle()
    }

    private fun showReceiver(recipientId: String) {
        composeTestRule.setContent {
            windowWidthDp = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastReceiverScreen(
                        onBack = { },
                        onNavigate = { },
                        onSeeCompletion = { },
                        onOpenFolder = { },
                        onEnded = { },
                        viewModel = BroadcastReceiverViewModel(
                            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to recipientId)),
                            formatters,
                        ),
                    )
                }
            }
        }
        settle()
    }

    private fun showSent() {
        composeTestRule.setContent {
            windowWidthDp = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSentScreen(
                        onBack = { },
                        onNavigate = { },
                        onEnded = { },
                        viewModel = BroadcastSentViewModel(formatters),
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
