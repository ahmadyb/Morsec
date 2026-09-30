package app.morsecode.ui.transfer

import androidx.lifecycle.SavedStateHandle
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplex session as a controller: what the route chooses, what the single whole-session
 * control offers, and what ending a session does.
 *
 * No coroutines and no looper: this view model holds its state directly rather than collecting
 * it, so what is asserted here is exactly what a screen would read.
 */
class TransferViewModelTest {

    private val formatters = MorseFormatters.forDefaultLocale()

    @Test
    fun `the route chooses which of the two views this is`() {
        val sending = viewModel(TransferLayout.SENDING_FIRST).state.value
        val receiving = viewModel(TransferLayout.RECEIVING_FIRST).state.value

        assertEquals(TransferDirection.OUTGOING, sending.summaryLines.first().direction)
        assertEquals(1, sending.summaryLines.size)
        assertEquals(TransferDirection.INCOMING, receiving.summaryLines.first().direction)
        assertEquals(2, receiving.summaryLines.size)
        assertEquals("the two views show the same session", sending.sessionId, receiving.sessionId)
        assertNotEquals("but from their own end", sending.peer.id, receiving.peer.id)
        assertEquals("the outbound view opens on the LAN phone", "LAN", sending.peer.transport)
        assertEquals("the inbound view opens on the Nearby phone", "Nearby", receiving.peer.transport)
    }

    @Test
    fun `a route that names no layout still opens a session`() {
        val state = viewModel(layoutId = "not-a-layout").state.value

        assertEquals(TransferLayout.SENDING_FIRST, state.layout)
        assertTrue(state.outbound.isNotEmpty())
    }

    @Test
    fun `every row prints the numbers of the file it belongs to`() {
        val rows = viewModel(TransferLayout.SENDING_FIRST).state.value.outbound
        val holiday = rows.first { it.item.id == "v1" }

        assertEquals("48.9 MB", holiday.transferred)
        assertEquals("144 MB", holiday.total)
        assertEquals("6.2 MB/s", holiday.speed)
        assertEquals("34%", holiday.percent)
        assertEquals("and the bar agrees with the label", 0.34f, holiday.item.fraction, 0.005f)
    }

    @Test
    fun `pausing one row leaves every other row alone`() {
        val viewModel = viewModel()
        val before = viewModel.state.value

        viewModel.pause("v1")

        val after = viewModel.state.value
        assertEquals(TransferState.PAUSED, after.outbound.first { it.item.id == "v1" }.item.state)
        assertEquals(
            "the inbound direction is untouched by a per-file action",
            before.inbound.map { it.item.state },
            after.inbound.map { it.item.state },
        )
        assertEquals(TransferState.QUEUED, after.outbound.first { it.item.id == "i1" }.item.state)
    }

    @Test
    fun `the whole-session control pauses while anything is running`() {
        val viewModel = viewModel()
        assertTrue(viewModel.state.value.allAction.enabled)
        assertEquals(TransferAllLabel.PAUSE_ALL, viewModel.state.value.allAction.label)

        viewModel.toggleAll()

        val paused = viewModel.state.value
        assertEquals(
            "every moving row in the session is held, in both directions",
            listOf(TransferState.PAUSED, TransferState.QUEUED, TransferState.PAUSED, TransferState.DONE, TransferState.FAILED, TransferState.VERIFYING),
            paused.outbound.map { it.item.state },
        )
        assertEquals(TransferState.PAUSED, paused.inbound.first().item.state)
        assertEquals(TransferState.DONE, paused.inbound[1].item.state)
    }

    @Test
    fun `the control offers resume once everything is held, and resuming sets each row going again`() {
        val viewModel = viewModel()
        viewModel.toggleAll()
        assertEquals(TransferAllLabel.RESUME_ALL, viewModel.state.value.allAction.label)
        assertTrue(viewModel.state.value.allAction.enabled)

        viewModel.toggleAll()

        val resumed = viewModel.state.value
        assertEquals(TransferAllLabel.PAUSE_ALL, resumed.allAction.label)
        assertEquals(
            "every held row goes again, including the one the fixture had already paused",
            listOf(
                TransferState.SENDING,
                TransferState.QUEUED,
                TransferState.SENDING,
                TransferState.DONE,
                TransferState.FAILED,
                TransferState.VERIFYING,
            ),
            resumed.outbound.map { it.item.state },
        )
        assertEquals(
            "an inbound file resumes receiving",
            TransferState.RECEIVING,
            resumed.inbound.first().item.state,
        )
    }

    @Test
    fun `the control is disabled when there is nothing it could do`() {
        val session = TransferSession(
            id = "s",
            peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
            outbound = listOf(finished()),
        )
        val state = transferUiStateTo(session, TransferLayout.SENDING_FIRST, formatters)

        assertFalse("nothing is running and nothing is held", state.allAction.enabled)
        assertEquals(TransferAllLabel.PAUSE_ALL, state.allAction.label)
    }

    @Test
    fun `clear completed empties one section only`() {
        val viewModel = viewModel()

        viewModel.clearCompleted(TransferDirection.OUTGOING)

        val after = viewModel.state.value
        assertTrue("the delivered outbound file is gone", after.outbound.none { it.item.id == "z1" })
        assertTrue(
            "the delivered inbound file is not",
            after.inbound.any { it.item.id == "i2" },
        )
        assertEquals("and nothing else in that section moved", 5, after.outbound.size)
    }

    @Test
    fun `clearing a section twice leaves it empty rather than failing`() {
        val viewModel = viewModel()

        viewModel.clearCompleted(TransferDirection.INCOMING)
        viewModel.clearCompleted(TransferDirection.INCOMING)

        val after = viewModel.state.value
        assertEquals(listOf("z2", "i3", "a2"), after.inbound.map { it.item.id })
        assertEquals("the other direction still has its completed file", 6, after.outbound.size)
    }

    @Test
    fun `clearing the completed rows leaves every other row where it was`() {
        val viewModel = viewModel()
        val before = viewModel.state.value.outbound.map { it.item.id }

        viewModel.clearCompleted(TransferDirection.OUTGOING)

        val after = viewModel.state.value.outbound.map { it.item.id }
        assertEquals(
            "only the delivered file goes, and the rest keep their order",
            before - "z1",
            after,
        )
        assertTrue(viewModel.state.value.outbound.all { it.item.state != TransferState.DONE })
    }

    @Test
    fun `a section whose rows are all completed can be emptied to nothing`() {
        val onlyDone = TransferSession(
            id = "s",
            peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
            outbound = listOf(finished()),
            inbound = listOf(finished(direction = TransferDirection.INCOMING, id = "done-2")),
        )

        val cleared = TransferRules.clearCompleted(onlyDone, TransferDirection.OUTGOING)
        val state = transferUiStateTo(cleared, TransferLayout.SENDING_FIRST, formatters)

        assertTrue("the section is empty, which is what the screen says next", state.outbound.isEmpty())
        assertTrue(
            "and its neighbour still has the file that arrived",
            state.inbound.any { it.item.state.isComplete },
        )
    }

    @Test
    fun `asking to end opens the question and changes nothing`() {
        val viewModel = viewModel()
        val before = viewModel.state.value.outbound.map { it.item.state }

        viewModel.requestEnd()

        assertTrue(viewModel.state.value.endConfirmationVisible)
        assertEquals(before, viewModel.state.value.outbound.map { it.item.state })
        // Moving, queued, held, arriving and being verified: five of the ten rows.
        assertEquals(5, viewModel.state.value.unfinishedCount)
    }

    @Test
    fun `dismissing the question leaves the session exactly as it was`() {
        val viewModel = viewModel()
        val before = viewModel.state.value

        viewModel.requestEnd()
        viewModel.dismissEnd()

        val after = viewModel.state.value
        assertFalse(after.endConfirmationVisible)
        assertEquals(before.outbound.map { it.item.state }, after.outbound.map { it.item.state })
        assertEquals(before.inbound.map { it.item.state }, after.inbound.map { it.item.state })
        assertEquals(before.allAction, after.allAction)
    }

    @Test
    fun `confirming ends the session and cancels what was unfinished`() {
        val viewModel = viewModel()

        viewModel.requestEnd()
        viewModel.confirmEnd()

        val after = viewModel.state.value
        assertFalse(after.endConfirmationVisible)
        assertEquals(
            listOf(
                TransferState.CANCELLED,
                TransferState.CANCELLED,
                TransferState.CANCELLED,
                TransferState.DONE,
                TransferState.FAILED,
                TransferState.CANCELLED,
            ),
            after.outbound.map { it.item.state },
        )
        assertEquals(0, after.unfinishedCount)
        assertFalse("nothing is left for the action bar to pause", after.allAction.enabled)
    }

    @Test
    fun `a row that has finished never offers anything, however the session is driven`() {
        val viewModel = viewModel()
        val completed = viewModel.state.value.outbound.first { it.item.state == TransferState.DONE }

        assertFalse(completed.item.actions.any)

        // Even after every whole-session action, the finished row is still finished.
        viewModel.toggleAll()
        val stillDone = viewModel.state.value.outbound.first { it.item.id == completed.item.id }
        assertEquals(TransferState.DONE, stillDone.item.state)
        assertFalse(stillDone.item.actions.any)
    }

    private fun finished(
        direction: TransferDirection = TransferDirection.OUTGOING,
        id: String = "done-1",
    ) = TransferItem(
        id = id,
        sessionId = "s",
        direction = direction,
        fileName = "notes_backup.zip",
        kind = MediaKind.ZIP,
        totalBytes = 18_200_000L,
        transferredBytes = 18_200_000L,
        state = TransferState.DONE,
        verification = VerificationOutcome.VERIFIED,
    )

    private fun viewModel(layout: TransferLayout = TransferLayout.SENDING_FIRST) =
        viewModel(layout.id)

    private fun viewModel(layoutId: String) = TransferViewModel(
        SavedStateHandle(mapOf(Routes.TRANSFER_ARG to layoutId)),
        formatters,
    )
}
