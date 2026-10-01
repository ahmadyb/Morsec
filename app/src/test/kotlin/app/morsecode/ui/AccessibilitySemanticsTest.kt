package app.morsecode.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.ThemeMode
import app.morsecode.ui.TestLifecycleOwner
import app.morsecode.ui.broadcast.BroadcastPickScreen
import app.morsecode.ui.broadcast.BroadcastPickerViewModel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The accessibility contract, asserted rather than assumed.
 *
 * Every check here is one the master prompt states outright: selected state is exposed,
 * progress reports its values, an action names the file it acts on, a completed row offers
 * nothing, and the destination the bottom bar lights up is reported to a screen reader the
 * same way it is drawn. The assertions are deliberately about *semantics* — what TalkBack
 * would read — and not about pixels, so a colour-only restyle that lost one of these would
 * fail here too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi", application = Application::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val formatters = MorseFormatters.forDefaultLocale()

    @Test
    fun `the selected files tab says it is selected and a tap moves the selection`() {
        showFiles()

        assertEquals(
            "Photos opens selected, and the semantics say so",
            true,
            selected("Photos"),
        )
        assertFalse("Videos starts unselected", selected("Videos"))

        composeTestRule.onNodeWithText("Videos").performClick()
        settle()

        assertTrue("Videos is now the selected tab", selected("Videos"))
        assertFalse("Photos gave the selection up", selected("Photos"))
    }

    @Test
    fun `the selection checkbox names its file and reports its checked state`() {
        showFiles()

        composeTestRule.onAllNodesWithContentDescription("Select")[0].performClick()
        settle()

        composeTestRule.onNodeWithContentDescription("IMG_2001.jpg").assertIsOff()
        composeTestRule.onNodeWithContentDescription("IMG_2001.jpg").performClick()
        settle()

        composeTestRule.onNodeWithContentDescription("IMG_2001.jpg").assertIsOn()
        composeTestRule.onNodeWithText("1 selected", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the bottom bar reports the destination it has lit`() {
        showPicker()

        // The broadcast flow is a Files-side destination: the reference keeps Files lit.
        assertTrue("Files is lit and says so", selected("Files"))
        assertFalse("Connect is not lit", selected("Connect"))
    }

    @Test
    fun `a transfer row's progress reports its values and its controls name the file`() {
        showTransfer(row("a", TransferState.SENDING))

        // The progress bar carries both a spoken current/total sentence and the numeric
        // range a screen reader turns into a percentage.
        composeTestRule.onNodeWithContentDescription(" of ", substring = true).assertIsDisplayed()
        composeTestRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertIsDisplayed()

        // And every control on the row says which file it would act on.
        composeTestRule.onNodeWithContentDescription("Pause a").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cancel a").assertIsDisplayed()
    }

    @Test
    fun `a completed row reports no transfer controls at all`() {
        showTransfer(row("done", TransferState.DONE))

        // The row is on screen — what is missing is the controls, not the file.
        composeTestRule.onNodeWithText("done").assertIsDisplayed()

        listOf("Pause", "Resume", "Cancel", "Retry").forEach { verb ->
            composeTestRule
                .onAllNodesWithContentDescription("$verb done")
                .assertCountEquals(0)
        }
    }

    @Test
    fun `the back control on a transfer screen is named for a screen reader`() {
        showTransfer(row("a", TransferState.SENDING))

        composeTestRule.onNodeWithContentDescription(context.getString(app.morsecode.R.string.action_back))
            .assertIsDisplayed()
    }

    // ---------------------------------------------------------------- helpers

    /** The `Selected` semantics a screen reader announces for the node carrying [text]. */
    private fun selected(text: String): Boolean =
        composeTestRule.onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Selected]

    private fun showFiles() {
        val viewModel = FilesViewModel(
            media = FakeMediaRepository(
                images = List(3) { index -> photo("IMG_200${index + 1}.jpg") },
            ),
            formatters = formatters,
        )
        composeTestRule.setContent {
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

    private fun showPicker() {
        val viewModel = BroadcastPickerViewModel(SavedStateHandle(), formatters)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastPickScreen(
                        onBack = { },
                        onNavigate = { },
                        onBroadcastTo = { },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showTransfer(vararg rows: TransferItem) {
        val session = TransferSession(
            id = "session-1",
            peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
            outbound = rows.toList(),
            inbound = emptyList(),
        )
        val state = transferUiStateTo(session, TransferLayout.SENDING_FIRST, formatters)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
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

    private fun row(
        name: String,
        state: TransferState,
        direction: TransferDirection = TransferDirection.OUTGOING,
    ) = TransferItem(
        id = "$direction-$name",
        sessionId = "session-1",
        direction = direction,
        fileName = name,
        kind = MediaKind.OTHER,
        totalBytes = 100L,
        transferredBytes = if (state == TransferState.DONE) 100L else 10L,
        speedBytesPerSecond = if (state.isActive) 6_200_000L else 0L,
        state = state,
    )

    private fun photo(name: String) = MediaItem(
        id = name,
        displayName = name,
        kind = MediaKind.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 4_100_000L,
        dateModifiedEpochMillis = System.currentTimeMillis(),
    )

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
