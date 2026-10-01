package app.morsecode.ui.connect

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.TestLifecycleOwner
import app.morsecode.ui.transfer.TransferLayout
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
 * The Connect screen's three destinations, with Broadcast the newest of them.
 *
 * The picker and its five screens exist behind one button here, so this is where the
 * chain starts: the button fires its own callback, Send and Receive keep theirs, and
 * nothing on this screen navigates on its own — the route each callback leads to is
 * the navigation layer's answer (see [app.morsecode.navigation.Routes], pinned by the
 * route tests), while this screen's whole responsibility is asking for the right one.
 *
 * The view model is real and runs against in-memory repositories, so the state under
 * the button — the peer count, the recent-devices section, the settings line — is the
 * screen's own state, not a stub's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class ConnectScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var helpOpens = 0
    private var broadcastOpens = 0
    private val transferOpens = mutableListOf<TransferLayout>()
    private val navigations = mutableListOf<MorseDestination>()

    private fun showScreen() {
        helpOpens = 0
        broadcastOpens = 0
        transferOpens.clear()
        navigations.clear()

        val viewModel = ConnectViewModel(
            FakeDeviceRepository(),
            FakeSettingsRepository(),
            MorseFormatters.forDefaultLocale(),
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(reducedMotion = true) {
                    ConnectScreen(
                        onNavigate = { navigations += it },
                        onOpenHelp = { helpOpens += 1 },
                        onOpenTransfer = { transferOpens += it },
                        onOpenBroadcast = { broadcastOpens += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    /** Walks the list to a control the viewport may have left out of composition. */
    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag(ConnectListTag)
            .performScrollToNode(hasText(text, substring = false))
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the broadcast entry opens the picker and nothing else`() {
        showScreen()

        scrollTo(BROADCAST_LABEL)
        composeTestRule.onAllNodesWithText(BROADCAST_LABEL).assertCountEquals(1)
        composeTestRule.onNodeWithText(BROADCAST_LABEL).assertIsEnabled()
        composeTestRule.onNodeWithText(BROADCAST_LABEL).performClick()
        settle()

        assertEquals(1, broadcastOpens)
        assertTrue(transferOpens.isEmpty())
        assertEquals(0, helpOpens)
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun `send and receive keep their own destinations`() {
        showScreen()

        scrollTo(SEND_LABEL)
        composeTestRule.onNodeWithText(SEND_LABEL).performClick()
        scrollTo(RECEIVE_LABEL)
        composeTestRule.onNodeWithText(RECEIVE_LABEL).performClick()
        settle()

        assertEquals(
            listOf(TransferLayout.SENDING_FIRST, TransferLayout.RECEIVING_FIRST),
            transferOpens,
        )
        assertEquals(0, broadcastOpens)
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun `help is its own action and does not open the broadcast flow`() {
        showScreen()

        // The help control is an icon: its label is announced, not printed.
        composeTestRule.onNodeWithContentDescription(HELP_LABEL).performClick()
        settle()

        assertEquals(1, helpOpens)
        assertEquals(0, broadcastOpens)
        assertTrue(transferOpens.isEmpty())
    }

    private companion object {
        // strings.xml: connect_broadcast, connect_send, connect_receive, connect_help.
        const val BROADCAST_LABEL = "⇶ Broadcast to several phones"
        const val SEND_LABEL = "↑ Send"
        const val RECEIVE_LABEL = "↓ Receive"
        const val HELP_LABEL = "Help"
    }
}
