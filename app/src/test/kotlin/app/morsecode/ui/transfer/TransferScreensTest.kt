package app.morsecode.ui.transfer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.ThemeMode
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.MorseDestination
import app.morsecode.navigation.Routes
import app.morsecode.ui.TestLifecycleOwner
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
import org.robolectric.shadows.ShadowToast

/**
 * Both duplex transfer views, rendered for real on the JVM.
 *
 * Two kinds of test live here, and the split is deliberate. The per-state matrix is asserted
 * against the stateless screen with a hand-written session holding one row, because the
 * question — "does a queued row offer exactly Cancel?" — should not depend on which fixture
 * rows happen to be composed inside a scrolling list. The two real views are then asserted
 * over the view model, for the things that are properties of the screen rather than of a row:
 * which section leads, what the headings count, that there is exactly one whole-session
 * control and no queue anywhere, and that the bottom bar stays reachable at the narrow end of
 * the phone range.
 *
 * Nothing here waits for anything: there is no clock in a transfer row, and a state that did
 * not move was not asked to move.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class TransferScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val formatters = MorseFormatters.forDefaultLocale()

    private var backPresses = 0
    private var navigations = emptyList<MorseDestination>()
    private var endedCount = 0
    private val cleared = mutableListOf<TransferDirection>()
    private val rowActions = mutableListOf<String>()
    private var allToggles = 0
    private var addFilesClicks = 0
    private var backgroundClicks = 0

    // ---------------------------------------------------------------- row matrix

    @Test
    fun `a moving outbound row offers pause and cancel`() {
        showStateless(session(outbound = listOf(row("a", TransferState.SENDING, kind = MediaKind.VIDEO))))

        assertRowControls(fileName = "a.bin", pause = true, resume = false, cancel = true, retry = false)
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.VIDEO.id).assertIsDisplayed()
    }

    @Test
    fun `a moving inbound row offers pause and cancel, the same way`() {
        showStateless(session(inbound = listOf(row("a", TransferState.RECEIVING, kind = MediaKind.IMAGE, TransferDirection.INCOMING))))

        assertRowControls(fileName = "a.bin", pause = true, resume = false, cancel = true, retry = false)
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.IMAGE.id).assertIsDisplayed()
    }

    @Test
    fun `a held row offers resume and cancel, and says where it would resume from`() {
        showStateless(
            session(
                outbound = listOf(
                    row("a", TransferState.PAUSED, kind = MediaKind.AUDIO, totalBytes = 64_000_000L, transferredBytes = 39_700_000L),
                ),
            ),
        )

        assertRowControls(fileName = "a.bin", pause = false, resume = true, cancel = true, retry = false)
        composeTestRule.onNodeWithText("39.7 MB / 64 MB · resume 39.7 MB").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.AUDIO.id).assertIsDisplayed()
    }

    @Test
    fun `a queued row offers cancel alone and says it is waiting`() {
        showStateless(session(outbound = listOf(row("a", TransferState.QUEUED, kind = MediaKind.DOC))))

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = true, retry = false)
        composeTestRule.onNodeWithText("4.1 MB · waiting").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.DOC.id).assertIsDisplayed()
    }

    @Test
    fun `a failed row offers retry alone`() {
        showStateless(session(outbound = listOf(row("a", TransferState.FAILED, kind = MediaKind.ZIP, transferredBytes = 190_000L))))

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = false, retry = true)
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.ZIP.id).assertIsDisplayed()
    }

    @Test
    fun `a completed row offers nothing at all`() {
        showStateless(
            session(
                outbound = listOf(
                    row("a", TransferState.DONE, kind = MediaKind.APK, totalBytes = 100L, transferredBytes = 100L)
                        .copy(verification = VerificationOutcome.VERIFIED),
                ),
            ),
        )

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = false, retry = false)
        composeTestRule.onNodeWithText("100 B · CRC verified").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.APK.id).assertIsDisplayed()
    }

    @Test
    fun `a verifying row offers nothing, because verification is not cancellable here`() {
        showStateless(session(outbound = listOf(row("a", TransferState.VERIFYING, kind = MediaKind.FOLDER, transferredBytes = 100L))))

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = false, retry = false)
        composeTestRule.onNodeWithText("100 B · verifying").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.FOLDER.id).assertIsDisplayed()
    }

    @Test
    fun `a cancelled row offers nothing and says who stopped it`() {
        showStateless(session(outbound = listOf(row("a", TransferState.CANCELLED, kind = MediaKind.OTHER, transferredBytes = 40L))))

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = false, retry = false)
        composeTestRule.onNodeWithText("40 B / 100 B · cancelled").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.OTHER.id).assertIsDisplayed()
    }

    @Test
    fun `a skipped row offers nothing and says it was skipped`() {
        showStateless(session(outbound = listOf(row("a", TransferState.SKIPPED, kind = MediaKind.VIDEO))))

        assertRowControls(fileName = "a.bin", pause = false, resume = false, cancel = false, retry = false)
        composeTestRule.onNodeWithText("100 B · skipped").assertIsDisplayed()
        // Whichever state it is in, the row draws the kind of file it is.
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.VIDEO.id).assertIsDisplayed()
    }

    @Test
    fun `a checked file whose checksum differed says so instead of claiming CRC verified`() {
        showStateless(
            session(
                outbound = listOf(
                    row("a", TransferState.DONE, totalBytes = 100L, transferredBytes = 100L)
                        .copy(verification = VerificationOutcome.MISMATCH),
                ),
            ),
        )

        composeTestRule.onNodeWithText("100 B · checksum differs").assertIsDisplayed()
    }

    @Test
    fun `each row draws the glyph of its own kind`() {
        showStateless(
            session(
                outbound = listOf(
                    row("v", TransferState.SENDING, kind = MediaKind.VIDEO),
                    row("i", TransferState.SENDING, kind = MediaKind.IMAGE),
                    row("a", TransferState.SENDING, kind = MediaKind.AUDIO),
                    row("z", TransferState.SENDING, kind = MediaKind.ZIP),
                ),
            ),
        )

        listOf(MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.ZIP).forEach { kind ->
            composeTestRule.onNodeWithTag(TransferKindTagPrefix + kind.id).assertExists()
        }
    }

    @Test
    fun `the status chip names the state`() {
        showStateless(
            session(
                outbound = listOf(
                    row("a", TransferState.SENDING),
                    row("b", TransferState.QUEUED),
                    row("c", TransferState.PAUSED),
                ),
            ),
        )

        composeTestRule.onNodeWithText("SENDING").assertIsDisplayed()
        composeTestRule.onNodeWithText("QUEUED").assertIsDisplayed()
        composeTestRule.onNodeWithText("PAUSED").assertIsDisplayed()
    }

    @Test
    fun `a row's progress is a range a screen reader can read`() {
        showStateless(session(outbound = listOf(row("a", TransferState.SENDING, transferredBytes = 50L))))

        val node = composeTestRule
            .onNodeWithContentDescription("50 B of 100 B, 50%")
            .fetchSemanticsNode()

        assertEquals(0.5f, node.config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        assertEquals(0f, node.config[SemanticsProperties.ProgressBarRangeInfo].range.start, 0f)
        assertEquals(1f, node.config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive, 0f)
    }

    @Test
    fun `every per-file control names the file it acts on`() {
        showStateless(
            session(
                outbound = listOf(
                    row("holiday_2019.mp4", TransferState.SENDING),
                    row("notes.zip", TransferState.QUEUED),
                    row("held.bin", TransferState.PAUSED),
                    row("broken.bin", TransferState.FAILED),
                ),
            ),
        )

        listOf(
            string(R.string.transfer_pause_file, "holiday_2019.mp4"),
            string(R.string.transfer_cancel_file, "holiday_2019.mp4"),
            string(R.string.transfer_cancel_file, "notes.zip"),
            string(R.string.transfer_resume_file, "held.bin"),
            string(R.string.transfer_retry_file, "broken.bin"),
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(description).assertExists()
        }
    }

    @Test
    fun `a row's controls report the row they belong to when they are pressed`() {
        showStateless(
            session(outbound = listOf(row("a", TransferState.SENDING), row("b", TransferState.SENDING))),
        )

        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_pause_file, "b.bin")).performClick()
        settle()

        assertEquals(listOf("b"), rowActions)
    }

    @Test
    fun `a section with no rows says so instead of showing nothing`() {
        showStateless(session(outbound = emptyList(), inbound = listOf(row("f", TransferState.RECEIVING))))

        composeTestRule.onNodeWithText(string(R.string.transfer_empty_sending_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transfer_empty_sending_body)).assertIsDisplayed()
    }

    @Test
    fun `clearing completed files is offered once per section and reaches only its own`() {
        showStateless(
            session(
                outbound = listOf(row("a", TransferState.DONE, transferredBytes = 100L)),
                inbound = listOf(row("b", TransferState.DONE, transferredBytes = 100L, direction = TransferDirection.INCOMING)),
            ),
        )

        // One heading action per section: two sections, two clear actions.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_clear_completed))
            .assertCountEquals(2)

        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_clear_completed))[1]
            .performClick()
        settle()

        assertEquals(
            "the second heading belongs to the second section",
            listOf(TransferDirection.INCOMING),
            cleared,
        )
    }

    @Test
    fun `the action bar is the master prompt's four actions and nothing else`() {
        showStateless(session(outbound = listOf(row("a", TransferState.SENDING))))

        listOf(
            R.string.transfer_add_files,
            R.string.transfer_pause_all,
            R.string.transfer_background,
            R.string.transfer_end,
        ).forEach { label ->
            composeTestRule.onNodeWithText(string(label)).assertIsDisplayed()
        }
        // The whole-session control appears exactly once, in the bar, and never in a heading.
        composeTestRule.onAllNodesWithText(string(R.string.transfer_pause_all)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.transfer_resume_all)).assertCountEquals(0)
    }

    @Test
    fun `the whole-session control becomes Resume all when the rows are held`() {
        showStateless(session(outbound = listOf(row("a", TransferState.PAUSED))))

        composeTestRule.onNodeWithText(string(R.string.transfer_resume_all)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.transfer_pause_all)).assertCountEquals(0)
    }

    @Test
    fun `there is no global retry and no queue anywhere on the transferred screen`() {
        showStateless(
            session(
                outbound = listOf(row("a", TransferState.FAILED), row("b", TransferState.SENDING)),
                inbound = listOf(row("c", TransferState.RECEIVING, direction = TransferDirection.INCOMING)),
            ),
        )

        // Retry stays per file: exactly one, for the one failed row.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_retry_file, "a.bin"))
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Retry failed").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Queue").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Send now").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Minimise").assertCountEquals(0)
    }

    @Test
    fun `the bottom actions stay reachable at the narrow end of the phone range`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(reducedMotion = true) {
                    DuplexTransferScreen(
                        state = stateFor(session(outbound = listOf(row("a", TransferState.SENDING)))),
                        onBack = {},
                        onNavigate = {},
                        onPause = {}, onResume = {}, onCancel = {}, onRetry = {},
                        onToggleAll = {},
                        onClearCompleted = {},
                        onAddFiles = {}, onBackground = {}, onRequestEnd = {}, onConfirmEnd = {}, onDismissEnd = {},
                    )
                }
            }
        }
        settle()

        listOf(
            R.string.transfer_add_files,
            R.string.transfer_pause_all,
            R.string.transfer_background,
            R.string.transfer_end,
        ).forEach { label ->
            composeTestRule.onNodeWithText(string(label)).assertIsDisplayed()
        }
        // And the file's own controls are still on screen rather than pushed off it.
        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_pause_file, "a.bin"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_cancel_file, "a.bin"))
            .assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi", application = Application::class)
    fun `a narrow phone keeps the row's name, its controls and its kind`() {
        showStateless(session(outbound = listOf(row("holiday_2019.mp4", TransferState.SENDING))))

        composeTestRule.onNodeWithText("holiday_2019.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TransferKindTagPrefix + MediaKind.OTHER.id).assertExists()
        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_pause_file, "holiday_2019.mp4"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_cancel_file, "holiday_2019.mp4"))
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------- the real views

    @Test
    fun `the outbound view is titled for both directions and leads with sending`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_title_sending)).assertIsDisplayed()
        composeTestRule.onNodeWithText(headingText(R.string.transfer_section_sending, 6)).assertIsDisplayed()
        assertEquals("Sending · 6 files", headingText(R.string.transfer_section_sending, 6))
        // And the other direction is on the same screen, further down it.
        scrollTo(headingText(R.string.transfer_section_receiving, 4))
        composeTestRule.onNodeWithText(headingText(R.string.transfer_section_receiving, 4)).assertIsDisplayed()
    }

    @Test
    fun `the inbound view is titled for sending back and leads with receiving`() {
        showScreen(TransferLayout.RECEIVING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_title_receiving)).assertIsDisplayed()
        composeTestRule.onNodeWithText(headingText(R.string.transfer_section_receiving, 4)).assertIsDisplayed()
        // And the section it sends back under is the one the reference calls "Sending back".
        scrollTo(headingText(R.string.transfer_section_sending_back, 6))
        composeTestRule.onNodeWithText(headingText(R.string.transfer_section_sending_back, 6)).assertIsDisplayed()
    }

    @Test
    fun `the outbound view's peer card names the LAN phone`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText("Ravi's Redmi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected · Phone · LAN").assertIsDisplayed()
        composeTestRule.onNodeWithText("LAN").assertIsDisplayed()
    }

    @Test
    fun `the inbound view's peer card names the Nearby phone`() {
        showScreen(TransferLayout.RECEIVING_FIRST)

        composeTestRule.onNodeWithText("Pixel 7X").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected · Phone · Nearby").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nearby").assertIsDisplayed()
    }

    @Test
    fun `the first rows of each view are the files the reference lists, with their states`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText("holiday_2019.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithText("SENDING").assertIsDisplayed()
        composeTestRule.onNodeWithText("48.9 MB / 144 MB · 6.2 MB/s").assertIsDisplayed()
    }

    @Test
    fun `nothing on the screen moves on its own`() {
        showScreen(TransferLayout.SENDING_FIRST)
        val bytesBefore = viewModel.state.value.outbound.map { it.transferredBytes }
        val speedsBefore = viewModel.state.value.outbound.map { it.speedBytesPerSecond }

        settle()
        ShadowLooper.idleMainLooper()
        settle()

        assertEquals(
            "there is no clock in a transfer row: idle as long as you like",
            bytesBefore,
            viewModel.state.value.outbound.map { it.transferredBytes },
        )
        assertEquals(speedsBefore, viewModel.state.value.outbound.map { it.speedBytesPerSecond })
        composeTestRule.onNodeWithText("48.9 MB / 144 MB · 6.2 MB/s").assertIsDisplayed()
    }

    @Test
    fun `the batch line counts the files of the direction it belongs to`() {
        showScreen(TransferLayout.SENDING_FIRST)

        scrollTo(string(R.string.transfer_summary_in_progress))

        composeTestRule.onNodeWithText(string(R.string.transfer_summary_in_progress)).assertIsDisplayed()
        composeTestRule.onNodeWithText("1 sending · 1 queued · 1 paused · 1 verifying · 1 failed")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("avg 6.2 MB/s").assertIsDisplayed()
    }

    @Test
    fun `add files says what this build cannot do yet rather than pretending`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_add_files)).performClick()
        settle()

        assertEquals(0, addFilesClicks)
        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            string(
                R.string.gated_body,
                string(R.string.gated_area_send),
                5,
                1,
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `background says that the service is what is missing`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_background)).performClick()
        settle()

        assertEquals(0, backgroundClicks)
        composeTestRule.onNodeWithText(
            string(R.string.gated_body, string(R.string.gated_area_background), 8, 1),
        ).assertIsDisplayed()
    }

    @Test
    fun `ending a session asks first, and dismissing the question changes nothing`() {
        showScreen(TransferLayout.SENDING_FIRST)
        val before = viewModel.state.value.outbound.map { it.item.state }

        composeTestRule.onNodeWithText(string(R.string.transfer_end)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.transfer_end_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("5 unfinished transfers will be cancelled.").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transfer_end_kept)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.transfer_end_keep)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.transfer_end_title)).assertDoesNotExist()
        assertEquals(before, viewModel.state.value.outbound.map { it.item.state })
        assertEquals("and the session is still open", 0, endedCount)
    }

    @Test
    fun `confirming the end cancels what was unfinished and leaves the screen`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_end)).performClick()
        settle()
        composeTestRule.onNodeWithText(string(R.string.transfer_end_confirm)).performClick()
        settle()

        assertEquals(1, endedCount)
        assertEquals(TransferState.CANCELLED, viewModel.state.value.outbound.first().item.state)
        assertEquals(
            "the delivered file is still delivered",
            TransferState.DONE,
            viewModel.state.value.outbound.first { it.item.id == "z1" }.item.state,
        )
        assertFalse(viewModel.state.value.endConfirmationVisible)
    }

    @Test
    fun `the whole-session control pauses both directions and the label changes`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithText(string(R.string.transfer_pause_all)).performClick()
        settle()

        assertEquals(1, allToggles)
        assertEquals(TransferState.PAUSED, viewModel.state.value.outbound.first().item.state)
        assertEquals(TransferState.PAUSED, viewModel.state.value.inbound.first().item.state)
        composeTestRule.onNodeWithText(string(R.string.transfer_resume_all)).assertIsDisplayed()
    }

    @Test
    fun `the section heading clears only the rows below it`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_clear_completed))[0]
            .performClick()
        settle()

        assertEquals(listOf(TransferDirection.OUTGOING), cleared)
        assertEquals("cleared, as the mockup's own toast says", string(R.string.transfer_cleared_done), ShadowToast.getTextOfLatestToast())
        assertTrue(viewModel.state.value.outbound.none { it.item.state.isComplete })
        assertTrue("the inbound section keeps its delivered file", viewModel.state.value.inbound.any { it.item.state.isComplete })
    }

    @Test
    fun `a section with nothing completed says so rather than doing nothing`() {
        showScreen(TransferLayout.RECEIVING_FIRST)

        // The incoming section's own completed file is cleared first, then asked again.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_clear_completed))[0]
            .performClick()
        settle()
        composeTestRule.onAllNodesWithContentDescription(string(R.string.transfer_clear_completed))[0]
            .performClick()
        settle()

        assertEquals(string(R.string.transfer_cleared_none), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `nothing on either view offers a queue, a minimise or a global retry`() {
        showScreen(TransferLayout.SENDING_FIRST)

        listOf(
            "Queue",
            "Send now",
            "Minimise",
            "Retry failed",
            string(R.string.action_more),
        ).forEach { forbidden ->
            composeTestRule.onNodeWithText(forbidden).assertDoesNotExist()
            composeTestRule.onNodeWithContentDescription(forbidden).assertDoesNotExist()
        }
    }

    @Test
    fun `the whole screen's controls all carry names`() {
        showScreen(TransferLayout.SENDING_FIRST)

        val descriptions = composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.ContentDescription] }

        assertTrue("no control may be announced as nothing", descriptions.none { it.isBlank() })
        assertTrue(
            "the back action and every per-file control are among them",
            descriptions.contains(string(R.string.action_back)) &&
                descriptions.contains(string(R.string.transfer_pause_file, "holiday_2019.mp4")),
        )
        // The bar's cells are named by their words, which is why their icons carry none.
        composeTestRule.onAllNodes(hasText(string(R.string.transfer_add_files)) and hasClickAction())
            .assertCountEquals(1)
    }

    @Test
    fun `back leaves the session`() {
        showScreen(TransferLayout.SENDING_FIRST)

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()

        assertEquals(1, backPresses)
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun `the bottom navigation is still the app's, with Files lit`() {
        showScreen(TransferLayout.SENDING_FIRST)

        listOf(
            R.string.nav_connect,
            R.string.nav_files,
            R.string.nav_history,
            R.string.nav_settings,
        ).forEach { label ->
            composeTestRule.onNodeWithText(string(label)).assertIsDisplayed()
        }
    }

    /** Both themes render the same controls: this is a smoke test, not a screenshot. */
    @Test
    fun `the screen renders in the light theme as well as the dark one`() {
        showStateless(session(outbound = listOf(row("a", TransferState.SENDING))), darkTheme = false)

        composeTestRule.onNodeWithText("a.bin").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.transfer_pause_file, "a.bin"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("SENDING").assertIsDisplayed()
    }

    // ------------------------------------------------------------------- helpers

    private lateinit var viewModel: TransferViewModel

    private fun showScreen(layout: TransferLayout) {
        backPresses = 0
        navigations = emptyList()
        endedCount = 0
        cleared.clear()
        rowActions.clear()
        allToggles = 0
        addFilesClicks = 0
        backgroundClicks = 0
        viewModel = TransferViewModel(
            SavedStateHandle(mapOf(Routes.TRANSFER_ARG to layout.id)),
            formatters,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(reducedMotion = true) {
                    TransferScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onEnded = { endedCount += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun showStateless(session: TransferSession, darkTheme: Boolean = true) {
        backPresses = 0
        navigations = emptyList()
        endedCount = 0
        cleared.clear()
        rowActions.clear()
        allToggles = 0

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT, reducedMotion = true) {
                    DuplexTransferScreen(
                        state = stateFor(session),
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        onPause = { rowActions += it.item.fileName },
                        onResume = { rowActions += it.item.fileName },
                        onCancel = { rowActions += it.item.fileName },
                        onRetry = { rowActions += it.item.fileName },
                        onToggleAll = { allToggles += 1 },
                        onClearCompleted = { cleared += it },
                        onAddFiles = { addFilesClicks += 1 },
                        onBackground = { backgroundClicks += 1 },
                        onRequestEnd = {},
                        onConfirmEnd = {},
                        onDismissEnd = {},
                    )
                }
            }
        }
        settle()
    }

    private fun stateFor(session: TransferSession) =
        transferUiStateTo(session, TransferLayout.SENDING_FIRST, formatters)

    private fun session(
        outbound: List<TransferItem> = emptyList(),
        inbound: List<TransferItem> = emptyList(),
    ) = TransferSession(
        id = "session-1",
        peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
        outbound = outbound,
        inbound = inbound,
    )

    private fun row(
        name: String,
        state: TransferState,
        direction: TransferDirection = TransferDirection.OUTGOING,
        kind: MediaKind = MediaKind.OTHER,
        totalBytes: Long = 100L,
        transferredBytes: Long = 10L,
    ) = TransferItem(
        id = "$direction-$name",
        sessionId = "session-1",
        direction = direction,
        fileName = name,
        kind = kind,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        speedBytesPerSecond = if (state.isActive) 6_200_000L else 0L,
        state = state,
    )

    private fun assertRowControls(
        fileName: String,
        pause: Boolean,
        resume: Boolean,
        cancel: Boolean,
        retry: Boolean,
    ) {
        assertControl(R.string.transfer_pause_file, fileName, pause)
        assertControl(R.string.transfer_resume_file, fileName, resume)
        assertControl(R.string.transfer_cancel_file, fileName, cancel)
        assertControl(R.string.transfer_retry_file, fileName, retry)
    }

    private fun assertControl(id: Int, fileName: String, present: Boolean) {
        val description = string(id, fileName)
        if (present) {
            composeTestRule.onNodeWithContentDescription(description).assertIsDisplayed()
        } else {
            composeTestRule.onNodeWithContentDescription(description).assertDoesNotExist()
        }
    }

    private fun headingText(labelId: Int, count: Int): String =
        context.resources.getQuantityString(R.plurals.transfer_section_files, count, string(labelId), count)

    /**
     * Scrolls the list to a node the way a user would.
     *
     * A LazyColumn composes only what is on screen, so anything below the first few rows has
     * to be scrolled to before it exists at all. This is the one place these tests reach past
     * the visible window, and it is stated rather than hidden.
     */
    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        settle()
    }

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
