package app.morsecode.ui.broadcast

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.ThemeMode
import app.morsecode.navigation.MorseDestination
import app.morsecode.navigation.Routes
import app.morsecode.ui.TestLifecycleOwner
import app.morsecode.ui.transfer.TransferAllLabel
import app.morsecode.ui.transfer.TransferKindTagPrefix
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
 * The five broadcast screens, rendered for real on the JVM.
 *
 * The screens are composed the way the app composes them — the real view models, the real
 * shared transfer row, the real action bar — and the assertions are about what a reviewer
 * would see: which phones the picker offers, which position each phone under a file is in,
 * what the batch summary counts, and what a finished broadcast is allowed to claim.
 *
 * **A list is not a screen.** A `LazyColumn` composes what fits, so counting nodes across a
 * list would assert about the viewport rather than about the screen. Every assertion about a
 * row therefore scrolls the screen's list to that row first: [scrollTo] throws when the row
 * cannot be reached, so a row that fell off the end of a list fails the test instead of passing
 * quietly. The one state the running app cannot reach yet — a broadcast that finished with a
 * failure and a skip — is drawn from a state built directly out of a session, because that is
 * the case whose wording must not be guessed.
 *
 * Nothing here waits for anything: there is no clock in a broadcast row, so a state that did
 * not move was never asked to move.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class BroadcastScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val formatters = MorseFormatters.forDefaultLocale()

    private var backPresses = 0
    private var navigations = emptyList<MorseDestination>()
    private var openedRecipients = emptyList<String>()
    private var broadcastsTo = emptyList<String>()
    private var endedClicks = 0
    private var completionsOpened = emptyList<String>()
    private var folderClicks = 0

    // ---------------------------------------------------------------- the picker

    @Test
    fun `the picker offers the discovered phones and nothing else`() {
        showPicker()

        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
        scrollTo("Pixel 7X")
        composeTestRule.onNodeWithText("Pixel 7X").assertIsDisplayed()
        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()

        // The laptop is in the discovered list and is not offered: a broadcast goes to phones.
        composeTestRule.onNodeWithText("Studio Laptop", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the picker says what would be sent and how many phones are chosen`() {
        showPicker()

        composeTestRule.onNodeWithText("LAN or Nearby").assertIsDisplayed()
        scrollTo("3 files · 212 MB")
        composeTestRule.onNodeWithText("3 files · 212 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("None yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pick at least two phones to broadcast — 2 more.")
            .assertIsDisplayed()
    }

    @Test
    fun `one chosen phone leaves the broadcast disabled and says how many more are needed`() {
        val viewModel = pickerViewModel()
        showPicker(viewModel)

        scrollTo("Pixel 7X")
        composeTestRule.onNodeWithText("Pixel 7X").performClick()
        settle()

        assertFalse(viewModel.state.value.canStart)
        assertEquals(1, viewModel.state.value.selectedCount)

        scrollTo("Connect & broadcast to (1)")
        composeTestRule.onNodeWithText("1 phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pick at least two phones to broadcast — 1 more.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect & broadcast to (1)").assertIsNotEnabled()

        // And pressing it anyway starts nothing.
        composeTestRule.onNodeWithText("Connect & broadcast to (1)").performClick()
        settle()
        assertTrue("a broadcast may not start on one phone", broadcastsTo.isEmpty())
    }

    @Test
    fun `two chosen phones turn the start on`() {
        val viewModel = pickerViewModel()
        showPicker(viewModel)

        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").performClick()
        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").performClick()
        settle()

        assertTrue(viewModel.state.value.canStart)
        assertEquals(2, viewModel.state.value.selectedCount)

        scrollTo("Connect & broadcast to (2)")
        composeTestRule.onNodeWithText("2 phones").assertIsDisplayed()
        composeTestRule.onNodeWithText("Each phone gets its own session.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect & broadcast to (2)").assertIsEnabled()
    }

    @Test
    fun `a chosen phone announces itself as selected, and tapping it again lets it go`() {
        showPicker()

        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsNotSelected()

        composeTestRule.onNodeWithText("Ravi's Redmi").performClick()
        settle()
        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsSelected()

        composeTestRule.onNodeWithText("Ravi's Redmi").performClick()
        settle()
        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsNotSelected()
    }

    @Test
    fun `starting the broadcast carries the chosen phones to the sender`() {
        showPicker()

        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").performClick()
        scrollTo("Pixel 7X")
        composeTestRule.onNodeWithText("Pixel 7X").performClick()
        settle()

        scrollTo("Connect & broadcast to (2)")
        composeTestRule.onNodeWithText("Connect & broadcast to (2)").performClick()
        settle()

        assertEquals("the picker hands the batch to the sender as one token", listOf("p,r"), broadcastsTo)
    }

    // ---------------------------------------------------------------- the sender

    @Test
    fun `the sender draws one block per file, with a row per phone beneath each`() {
        val viewModel = senderViewModel()
        showSender(viewModel)

        assertEquals("the batch is three files", 3, viewModel.state.value.files.size)
        viewModel.state.value.files.forEach { file ->
            assertEquals(
                "every file carries one row per chosen phone",
                3,
                file.recipients.size,
            )
        }

        composeTestRule.onNodeWithText("holiday_2019.mp4").assertIsDisplayed()
        listOf("IMG_2043.jpg", "live_set_final.flac").forEach { file ->
            scrollTo(file)
            composeTestRule.onNodeWithText(file).assertIsDisplayed()
        }

        scrollTo("holiday_2019.mp4")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pixel 7X").assertIsDisplayed()
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()
    }

    @Test
    fun `three phones under one file show three different positions`() {
        showSender()

        // The audio file is the one the phones disagree about: part-way to Ravi, delivered to
        // the Pixel, failed part-way to Samsung. Nothing here is a batch-wide percentage.
        listOf("33%", "100%", "19%").forEach { percent ->
            scrollTo(percent)
            composeTestRule.onNodeWithText(percent).assertIsDisplayed()
        }
    }

    @Test
    fun `a file says what it is, how big it is and how many phones have it`() {
        showSender()

        composeTestRule.onNodeWithText("144 MB · 2 of 3 phones").assertIsDisplayed()
        scrollTo("4.1 MB · 3 of 3 phones")
        composeTestRule.onNodeWithText("4.1 MB · 3 of 3 phones").assertIsDisplayed()
    }

    @Test
    fun `the batch summary counts the deliveries and the bytes they owe`() {
        showSender()

        scrollTo("Broadcasting to 3 phones")
        composeTestRule.onNodeWithText("Broadcasting to 3 phones").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 sending · 1 paused · 1 failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("combined throughput 4.8 MB/s").assertIsDisplayed()
        composeTestRule.onNodeWithText("phones").assertIsDisplayed()
        composeTestRule.onNodeWithText("files each").assertIsDisplayed()
        composeTestRule.onNodeWithText("to send MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()
    }

    @Test
    fun `only the failed delivery offers a retry, and it names the file and the phone`() {
        showSender()

        scrollToDescription("Retry live_set_final.flac for Samsung A14")
        composeTestRule
            .onNodeWithContentDescription("Retry live_set_final.flac for Samsung A14")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Retry", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun `retrying one phone changes that phone's row and leaves its neighbours alone`() {
        val viewModel = senderViewModel()
        showSender(viewModel)

        scrollToDescription("Retry live_set_final.flac for Samsung A14")
        composeTestRule
            .onNodeWithContentDescription("Retry live_set_final.flac for Samsung A14")
            .performClick()
        settle()

        val live = viewModel.state.value.files.first { it.file.id == BroadcastFixtures.live.id }
        assertEquals(
            "the failed phone restarts from nothing; the phones beside it keep what they had",
            listOf("33%", "100%", ""),
            live.recipients.map { it.percent },
        )
        assertEquals(
            "the retried delivery is moving again beside the one that never stopped",
            2,
            viewModel.state.value.result.active,
        )

        // What is drawn is that: no percentage where nothing has moved, the two healthy rows
        // exactly as they were, and the failed phone no longer showing its last position.
        scrollTo("33%")
        composeTestRule.onNodeWithText("33%").assertIsDisplayed()
        composeTestRule.onNodeWithText("100%").assertIsDisplayed()
        composeTestRule.onNodeWithText("19%").assertDoesNotExist()
    }

    @Test
    fun `the sender's bar is the transfer bar, with one control for the whole batch`() {
        showSender()

        composeTestRule.onNodeWithText("Add files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    @Test
    fun `one tap on the bar holds every phone, and the same cell offers to let them go`() {
        val viewModel = senderViewModel()
        showSender(viewModel)

        composeTestRule.onNodeWithText("Pause all").performClick()
        settle()

        assertEquals(TransferAllLabel.RESUME_ALL, viewModel.state.value.allAction.label)
        assertEquals("nothing is moving any more", 0, viewModel.state.value.result.active)
        composeTestRule.onNodeWithText("Resume all").assertIsDisplayed()
    }

    @Test
    fun `tapping a phone's row opens that phone's own screen`() {
        showSender()

        scrollTo("Samsung A14")
        composeTestRule.onAllNodesWithText("Samsung A14").onFirst().performClick()
        settle()

        assertEquals(listOf("s"), openedRecipients)
    }

    @Test
    fun `each file draws the glyph for its own kind`() {
        showSender()

        composeTestRule.onNodeWithTag(TransferKindTagPrefix + "video").assertIsDisplayed()

        scrollTo("IMG_2043.jpg")
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + "image").assertIsDisplayed()

        scrollTo("live_set_final.flac")
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + "audio").assertIsDisplayed()
    }

    @Test
    fun `ending the broadcast asks first, and keeps going when told to`() {
        val viewModel = senderViewModel()
        showSender(viewModel)

        composeTestRule.onNodeWithText("End").performClick()
        settle()

        assertTrue(viewModel.state.value.endConfirmationVisible)
        composeTestRule.onNodeWithText("End this session?").assertIsDisplayed()
        assertTrue(
            "asking changes nothing about the batch",
            viewModel.state.value.result.pending > 0,
        )
        assertEquals(0, endedClicks)

        composeTestRule.onNodeWithText("Keep transferring").performClick()
        settle()

        assertFalse(viewModel.state.value.endConfirmationVisible)
        assertEquals("nothing was ended", 0, endedClicks)
    }

    @Test
    fun `a finished broadcast offers the completion screen and nothing left to hold`() {
        showSenderState(broadcastSenderState(BroadcastFixtures.completedSession(), formatters))

        scrollTo("✓ Batch complete")
        composeTestRule.onNodeWithText("✓ Batch complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pause all").assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Retry", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Cancel", substring = true).assertCountEquals(0)

        scrollTo("See completion")
        composeTestRule.onNodeWithText("See completion").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- one receiver

    @Test
    fun `the receiver says who it is receiving from, over the broadcast badge`() {
        showReceiver("s")

        composeTestRule.onNodeWithText("Broadcast · Phone · LAN").assertIsDisplayed()
        composeTestRule.onNodeWithText("FROM").assertIsDisplayed()
    }

    @Test
    fun `the receiver draws that phone's files and that phone's positions`() {
        showReceiver("s")

        composeTestRule.onNodeWithText("3 files · 212 MB").assertIsDisplayed()

        composeTestRule.onNodeWithText("holiday_2019.mp4").assertIsDisplayed()
        scrollTo("PAUSED")
        composeTestRule.onNodeWithText("PAUSED").assertIsDisplayed()

        scrollTo("IMG_2043.jpg")
        composeTestRule.onNodeWithText("IMG_2043.jpg").assertIsDisplayed()

        scrollTo("live_set_final.flac")
        composeTestRule.onNodeWithText("FAILED").assertIsDisplayed()
    }

    @Test
    fun `a receiver's rows carry no per-file controls`() {
        showReceiver("s")

        composeTestRule.onAllNodesWithContentDescription("Pause", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Cancel", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Retry", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the receiver counts its own deliveries, not the batch's`() {
        showReceiver("s")

        scrollTo("1 paused · 1 failed")
        composeTestRule.onNodeWithText("1 paused · 1 failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Receiving from MYA-L10").assertIsDisplayed()
    }

    @Test
    fun `clearing on a receiver removes what arrived there and keeps the rest`() {
        val viewModel = BroadcastReceiverViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to "s")),
            formatters,
        )
        showReceiver(viewModel)

        scrollTo("IMG_2043.jpg")
        composeTestRule.onNodeWithContentDescription("Clear completed").performClick()
        settle()

        assertEquals("the delivered file left the list", 2, viewModel.state.value.rows.size)
        composeTestRule.onNodeWithText("DONE").assertDoesNotExist()
        scrollTo("FAILED")
        composeTestRule.onNodeWithText("FAILED").assertIsDisplayed()
    }

    @Test
    fun `a phone that has everything can open its own completion, and its folder is gated`() {
        showReceiver("p")

        scrollTo("✓ Batch complete")
        composeTestRule.onNodeWithText("✓ Batch complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved to Download/Morsecode").assertIsDisplayed()

        scrollTo("See completion")
        composeTestRule.onNodeWithText("See completion").performClick()
        settle()
        assertEquals(listOf("p"), completionsOpened)

        // Opening the destination is milestone 5's work, so it explains itself instead of
        // pretending to open a folder that no transfer has written to yet.
        scrollTo("Open folder")
        composeTestRule.onNodeWithText("Open folder").performClick()
        settle()
        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
        assertEquals("no folder was opened", 0, folderClicks)
    }

    // ---------------------------------------------------------------- the completions

    @Test
    fun `the sender's completion reports every phone and every delivery`() {
        showSent()

        composeTestRule.onNodeWithText("Broadcast complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("All 3 phones verified").assertIsDisplayed()
        composeTestRule.onNodeWithText("×3").assertIsDisplayed()
        scrollTo("Delivered to")
        composeTestRule.onNodeWithText("Delivered to").assertIsDisplayed()

        scrollTo("Ravi's Redmi")
        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 of 3 files · 212 MB · verified").assertIsDisplayed()

        scrollTo("9 delivered")
        composeTestRule.onNodeWithText("9 delivered").assertIsDisplayed()
        composeTestRule.onNodeWithText("636 MB").assertIsDisplayed()
    }

    @Test
    fun `a broadcast that did not come out clean may not claim that every phone verified`() {
        showSentState(broadcastSentState(BroadcastFixtures.partialSession(), formatters))

        composeTestRule.onNodeWithText("Broadcast complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("All 3 phones verified").assertDoesNotExist()
        composeTestRule.onNodeWithText("7 of 9 deliveries verified").assertIsDisplayed()
        composeTestRule.onNodeWithText("×3").assertDoesNotExist()

        scrollTo("7 delivered · 1 failed · 1 skipped")
        composeTestRule.onNodeWithText("7 delivered · 1 failed · 1 skipped").assertIsDisplayed()
    }

    @Test
    fun `a phone whose files did not all arrive is listed with what went wrong`() {
        showSentState(broadcastSentState(BroadcastFixtures.partialSession(), formatters))

        scrollTo("Samsung A14")
        composeTestRule.onNodeWithText("Samsung A14").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 of 3 files · 156 MB · 1 failed · 1 skipped")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Retry", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun `clearing the sender's list says so and keeps the report`() {
        val viewModel = BroadcastSentViewModel(formatters)
        showSent(viewModel)

        scrollTo("Clear")
        composeTestRule.onNodeWithText("Clear").performClick()
        settle()

        assertTrue(viewModel.state.value.cleared)
        composeTestRule.onNodeWithText("Nothing delivered yet").assertIsDisplayed()
        scrollTo("9 delivered")
        composeTestRule.onNodeWithText("9 delivered").assertIsDisplayed()
    }

    @Test
    fun `the receiver's completion lists what arrived, verified, and where it went`() {
        showReceived("p")

        composeTestRule.onNodeWithText("Received").assertIsDisplayed()
        composeTestRule.onNodeWithText("holiday_2019.mp4").assertIsDisplayed()

        scrollTo("✓ Batch complete")
        composeTestRule.onNodeWithText("✓ Batch complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 received").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved to Download/Morsecode").assertIsDisplayed()
        composeTestRule.onNodeWithText("files").assertIsDisplayed()
    }

    @Test
    fun `a completed row shows no transfer control anywhere on a completion screen`() {
        showReceived("p")

        composeTestRule.onAllNodesWithText("PAUSED").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Pause", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Resume", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Cancel", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Retry", substring = true).assertCountEquals(0)
    }

    @Test
    fun `back leaves whichever broadcast screen is showing`() {
        showReceiver("s")

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()

        assertEquals(1, backPresses)
    }

    // ---------------------------------------------------------------- helpers

    private fun pickerViewModel(): BroadcastPickerViewModel =
        BroadcastPickerViewModel(SavedStateHandle(), formatters)

    private fun senderViewModel(): BroadcastSenderViewModel = BroadcastSenderViewModel(
        SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to "r,p,s")),
        formatters,
    )

    /** Scroll the screen's list until the row carrying this text is composed and visible. */
    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag(BroadcastListTag)
            .performScrollToNode(hasText(text, substring = false))
        settle()
    }

    /** The same, for a control that is named by its accessibility description. */
    private fun scrollToDescription(description: String) {
        composeTestRule.onNodeWithTag(BroadcastListTag)
            .performScrollToNode(hasContentDescription(description, substring = false))
        settle()
    }

    private fun showPicker(viewModel: BroadcastPickerViewModel = pickerViewModel()) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastPickScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onBroadcastTo = { broadcastsTo = broadcastsTo + it },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showSender(viewModel: BroadcastSenderViewModel) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSenderScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onSeeCompletion = { },
                        onOpenRecipient = { openedRecipients = openedRecipients + it },
                        onEnded = { endedClicks += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showSender() {
        showSender(senderViewModel())
    }

    private fun showSenderState(state: BroadcastSenderUiState) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSenderContent(
                        state = state,
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onRetry = { },
                        onToggleAll = { },
                        onAddFiles = { },
                        onBackground = { },
                        onRequestEnd = { },
                        onConfirmEnd = { },
                        onDismissEnd = { },
                        onSeeCompletion = { },
                        onOpenRecipient = { },
                    )
                }
            }
        }
        settle()
    }

    private fun showReceiver(recipientId: String) = showReceiver(
        BroadcastReceiverViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to recipientId)),
            formatters,
        ),
    )

    private fun showReceiver(viewModel: BroadcastReceiverViewModel) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastReceiverScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onSeeCompletion = { completionsOpened = completionsOpened + it },
                        onOpenFolder = { folderClicks += 1 },
                        onEnded = { endedClicks += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showSent(viewModel: BroadcastSentViewModel = BroadcastSentViewModel(formatters)) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSentScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onEnded = { endedClicks += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showSentState(state: BroadcastSentUiState) {
        reset()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastSentContent(
                        state = state,
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onClear = { },
                        onAddFiles = { },
                        onBackground = { },
                        onEnded = { endedClicks += 1 },
                    )
                }
            }
        }
        settle()
    }

    private fun showReceived(recipientId: String) {
        reset()
        val viewModel = BroadcastReceivedViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to recipientId)),
            formatters,
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = ThemeMode.DARK, reducedMotion = true) {
                    BroadcastReceivedScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onOpenFolder = { folderClicks += 1 },
                        onEnded = { endedClicks += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun reset() {
        backPresses = 0
        navigations = emptyList()
        openedRecipients = emptyList()
        broadcastsTo = emptyList()
        endedClicks = 0
        completionsOpened = emptyList()
        folderClicks = 0
    }

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
