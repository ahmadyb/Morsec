package app.morsecode.ui.broadcast

import androidx.lifecycle.SavedStateHandle
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.Routes
import app.morsecode.ui.transfer.TransferAllLabel
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five screens' controllers: what a tap does to a session, and what the screen is handed
 * afterwards.
 *
 * These are not composition tests. Each view model is asked a question the way a screen asks
 * it — toggle this phone, retry that delivery, end the batch — and the answer is read off the
 * published state, which is the same value the screen draws. The point of the file is the
 * scoping: a retry, a hold or an end that names one phone must move that phone and nothing
 * else, and a screen must never be handed a number its own rows contradict.
 */
class BroadcastViewModelsTest {

    private val formatters = MorseFormatters.forDefaultLocale()

    private val holiday: String = BroadcastFixtures.holiday.id
    private val photo: String = BroadcastFixtures.photo.id
    private val live: String = BroadcastFixtures.live.id

    // ---------------------------------------------------------------- the picker

    @Test
    fun `the picker opens on everything discovery found and the batch it would send`() {
        val viewModel = BroadcastPickerViewModel(SavedStateHandle(), formatters)

        assertEquals(listOf("r", "p", "s"), viewModel.state.value.peers.map { it.peerId })
        assertEquals(0, viewModel.state.value.selectedCount)
        assertEquals(3, viewModel.state.value.batchFileCount)
        assertEquals("212 MB", viewModel.state.value.batchBytes)
        assertFalse("two phones are needed and none are chosen", viewModel.state.value.canStart)
        assertFalse("discovery is not live in this build", viewModel.state.value.discoveryLive)
    }

    @Test
    fun `choosing phones turns the start on at the second one`() {
        val viewModel = BroadcastPickerViewModel(SavedStateHandle(), formatters)

        viewModel.toggle("r")
        assertFalse(viewModel.state.value.canStart)
        assertEquals(1, viewModel.state.value.shortfall)

        viewModel.toggle("p")
        assertTrue(viewModel.state.value.canStart)
        assertEquals(0, viewModel.state.value.shortfall)
        assertEquals("r,p", viewModel.chosenToken())
    }

    @Test
    fun `choosing a laptop is refused by the picker as well as by the model`() {
        val viewModel = BroadcastPickerViewModel(SavedStateHandle(), formatters)

        viewModel.toggle("o")

        assertEquals("nothing was chosen and nothing was published", 0, viewModel.state.value.selectedCount)
        assertEquals("", viewModel.chosenToken())
    }

    @Test
    fun `a recreated picker comes back with the same phones ticked`() {
        val first = BroadcastPickerViewModel(SavedStateHandle(), formatters)
        first.toggle("s")
        first.toggle("p")

        val saved = SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to first.chosenToken()))
        val restored = BroadcastPickerViewModel(saved, formatters)

        assertEquals(setOf("p", "s"), restored.state.value.selection.chosen)
        assertTrue(restored.state.value.canStart)
        assertEquals(first.chosenToken(), restored.chosenToken())
    }

    @Test
    fun `a restored picker thrown a phone it never offered ignores it`() {
        val saved = SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to "r,ghost,p"))

        val restored = BroadcastPickerViewModel(saved, formatters)

        assertEquals(setOf("r", "p"), restored.state.value.selection.chosen)
    }

    // ---------------------------------------------------------------- the sender

    @Test
    fun `the sender opens on the phones the picker chose`() {
        val state = senderState(chosen = "r,s")

        assertEquals(listOf("r", "s"), state.files.first().recipients.map { it.recipient.peerId })
        assertEquals(2, state.recipientCount)
        assertEquals("three files to two phones", 6, state.result.expected)
    }

    @Test
    fun `the sender opens on the reference's own two phones when the route names none`() {
        val state = senderState(chosen = "")

        assertEquals(2, state.recipientCount)
        assertEquals(listOf("r", "p"), state.files.first().recipients.map { it.recipient.peerId })
    }

    @Test
    fun `every phone under a file has its own percentage, and no two are copied`() {
        val state = senderState(chosen = "r,p,s")
        val live = state.files.first { it.file.id == live }

        assertEquals(
            "Ravi is part-way, Pixel is done, Samsung failed part-way — three different rows",
            listOf("33%", "100%", "19%"),
            live.recipients.map { it.percent },
        )
        assertEquals(
            listOf(TransferState.SENDING, TransferState.DONE, TransferState.FAILED),
            live.recipients.map { it.state },
        )
    }

    @Test
    fun `a retry moves one phone's delivery and leaves the phone beside it exactly as it was`() {
        val viewModel = senderViewModel(chosen = "r,p,s")
        val before = viewModel.state.value.files.first { it.file.id == live }.recipients

        viewModel.retry(recipientId = "s", fileId = live)

        val after = viewModel.state.value.files.first { it.file.id == live }.recipients
        assertEquals(
            "the failed phone restarts from nothing, and says nothing rather than zero",
            listOf("33%", "100%", ""),
            after.map { it.percent },
        )
        assertEquals(TransferState.SENDING, after[2].state)
        assertEquals(
            "and the two phones that were fine are the same values they were",
            listOf(before[0], before[1]),
            listOf(after[0], after[1]),
        )
    }

    @Test
    fun `the whole-batch control holds every phone and offers to let them go again`() {
        val viewModel = senderViewModel(chosen = "r,p")
        assertEquals(TransferAllLabel.PAUSE_ALL, viewModel.state.value.allAction.label)

        viewModel.toggleAll()

        val paused = viewModel.state.value
        assertTrue("nothing is moving", paused.result.active == 0)
        assertTrue(paused.result.paused > 0)
        assertEquals(TransferAllLabel.RESUME_ALL, paused.allAction.label)
        assertTrue(paused.files.flatMap { it.recipients }.none { it.state.isActive })
        assertEquals(
            "nothing was delivered away and nothing that had arrived was lost",
            5,
            paused.result.delivered,
        )
    }

    @Test
    fun `the sender's summary counts the batch, not one file of it`() {
        val state = senderState(chosen = "r,p,s")

        assertEquals("3", state.tiles.phones)
        assertEquals("3", state.tiles.filesEach)
        assertEquals("636 MB", state.tiles.toSend)
        assertEquals("the biggest file is 144 MB of the 212 MB batch", "144 MB", state.files.first { it.file.id == holiday }.size)
        assertTrue("nothing finished yet", state.result.pending > 0)
        assertFalse(state.complete)
    }

    @Test
    fun `ending the batch asks first, and only ends it when the question is answered`() {
        val viewModel = senderViewModel(chosen = "r,p,s")
        assertTrue(viewModel.state.value.result.pending > 0)

        viewModel.requestEnd()
        assertTrue(viewModel.state.value.endConfirmationVisible)
        assertTrue("asking changes nothing about the batch", viewModel.state.value.result.pending > 0)

        viewModel.dismissEnd()
        assertFalse(viewModel.state.value.endConfirmationVisible)
        assertTrue(viewModel.state.value.result.pending > 0)

        viewModel.requestEnd()
        viewModel.confirmEnd()
        assertFalse(viewModel.state.value.endConfirmationVisible)
        assertEquals("nothing is left unfinished", 0, viewModel.state.value.result.pending)
    }

    @Test
    fun `a broadcast that has finished offers nothing to hold`() {
        val state = broadcastSenderState(BroadcastFixtures.completedSession(), formatters)

        assertTrue(state.complete)
        assertFalse("the bar's one control is disabled rather than wrong", state.allAction.enabled)
        assertTrue(state.files.all { it.state.isFinished })
        assertTrue(state.files.all { file -> file.recipients.all { !it.actions.any } })
    }

    // ---------------------------------------------------------------- one receiver

    @Test
    fun `the receiver draws the phone it was asked for and no other phone at all`() {
        val state = BroadcastFixtures.session().let { broadcastReceiverState(it, "s", formatters) }

        assertEquals("s", state.recipient.peerId)
        assertEquals(3, state.rows.size)
        assertEquals(
            "Samsung's own files, in order: held, delivered, failed",
            listOf(TransferState.PAUSED, TransferState.DONE, TransferState.FAILED),
            state.rows.map { it.item.state },
        )
        assertTrue(
            "and every row is incoming, because this is the receiving end",
            state.rows.all { it.item.direction == TransferDirection.INCOMING },
        )
        assertEquals(
            "and the rows are this phone's files, in the batch's order",
            listOf("s_$holiday", "s_$photo", "s_$live"),
            state.rows.map { it.item.id },
        )
    }

    @Test
    fun `the receiver's own numbers are that phone's, not the batch's`() {
        val session = BroadcastFixtures.session()

        val pixel = broadcastReceiverState(session, "p", formatters)
        assertEquals(3, pixel.result.delivered)
        assertTrue("Pixel has every file", pixel.complete)

        val samsung = broadcastReceiverState(session, "s", formatters)
        assertEquals(1, samsung.result.delivered)
        assertEquals(1, samsung.result.failed)
        assertFalse("Samsung does not", samsung.complete)
    }

    @Test
    fun `a route naming a phone that is not here falls back instead of crashing`() {
        val state = BroadcastFixtures.session().let { broadcastReceiverState(it, "nobody", formatters) }

        assertNotNull(state.recipient)
        assertEquals(3, state.rows.size)
    }

    @Test
    fun `clearing on one phone removes what arrived there and leaves the rest`() {
        val viewModel = BroadcastReceiverViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to "s")),
            formatters,
        )
        assertEquals(3, viewModel.state.value.rows.size)
        assertEquals(1, viewModel.completedCount())

        viewModel.clearCompleted()

        assertEquals("the delivered file left the list", 2, viewModel.state.value.rows.size)
        assertTrue(viewModel.state.value.rows.none { it.item.state == TransferState.DONE })
        assertTrue(
            "the held file and the failed one are still there to act on",
            viewModel.state.value.rows.any { it.item.state == TransferState.PAUSED },
        )
        assertEquals(0, viewModel.completedCount())
    }

    @Test
    fun `the receiver's single control holds and releases the whole batch on that phone`() {
        // Ravi is the phone with something still moving in the sample fan-out.
        val viewModel = BroadcastReceiverViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to "r")),
            formatters,
        )
        assertEquals(TransferAllLabel.PAUSE_ALL, viewModel.state.value.allAction.label)
        assertTrue(viewModel.state.value.allAction.enabled)
        assertTrue(viewModel.state.value.rows.any { it.item.state == TransferState.RECEIVING })

        viewModel.toggleAll()

        assertTrue("nothing on this phone is moving any more", viewModel.state.value.rows.none { it.item.state.isActive })
        assertEquals(TransferAllLabel.RESUME_ALL, viewModel.state.value.allAction.label)
        assertTrue(viewModel.state.value.rows.any { it.item.state == TransferState.PAUSED })
    }

    @Test
    fun `the receiver's own end asks, and ends only that phone's share`() {
        val viewModel = BroadcastReceiverViewModel(
            SavedStateHandle(mapOf(Routes.BROADCAST_RECIPIENT_ARG to "s")),
            formatters,
        )

        viewModel.requestEnd()
        assertTrue(viewModel.state.value.endConfirmationVisible)

        viewModel.confirmEnd()
        assertFalse(viewModel.state.value.endConfirmationVisible)
        assertEquals("that phone is finished", 0, viewModel.state.value.result.pending)
        assertTrue(viewModel.state.value.complete)
    }

    // ---------------------------------------------------------------- the two completions

    @Test
    fun `the sender's completion lists every phone with what it received`() {
        val state = broadcastSentState(BroadcastFixtures.completedSession(), formatters)

        assertEquals(3, state.recipients.size)
        assertTrue("every phone verified everything", state.result.fullySuccessful)
        assertTrue(state.recipients.all { it.clean })
        assertTrue(state.recipients.all { it.delivered == 3 && it.expected == 3 })
        assertEquals("636 MB", state.deliveredBytes)
        assertFalse(state.cleared)
    }

    @Test
    fun `a partial broadcast is complete and is not reported as a success`() {
        val state = broadcastSentState(BroadcastFixtures.partialSession(), formatters)

        assertTrue("every delivery reached a terminal state", state.result.complete)
        assertFalse("one delivery failed and one was skipped", state.result.fullySuccessful)
        assertEquals(1, state.result.failed)
        assertEquals(1, state.result.skipped)
        assertFalse(
            "the phone whose files did not all arrive is not clean",
            state.recipients.first { it.peer.peerId == "s" }.clean,
        )
        assertTrue(
            "and the phones that were fine are clean",
            state.recipients.filter { it.peer.peerId != "s" }.all { it.clean },
        )
    }

    @Test
    fun `the sender's completion can be cleared without losing the report`() {
        val viewModel = BroadcastSentViewModel(formatters)
        assertFalse(viewModel.state.value.cleared)

        viewModel.clear()

        assertTrue(viewModel.state.value.cleared)
        assertTrue(viewModel.state.value.recipients.isEmpty())
        assertEquals(
            "the batch's own numbers are still there",
            9,
            viewModel.state.value.result.expected,
        )
        assertEquals("636 MB", viewModel.state.value.deliveredBytes)
    }

    @Test
    fun `the receiver's completion reports that phone alone`() {
        val state = BroadcastFixtures.session().let { broadcastReceivedState(it, "p", formatters) }

        assertEquals("p", state.recipient.peerId)
        assertEquals(3, state.fileCount)
        assertEquals(3, state.result.delivered)
        assertTrue(state.complete)
        assertTrue(
            "every row is a finished incoming file with no control left on it",
            state.rows.all { it.item.state == TransferState.DONE && !it.item.actions.any },
        )
    }

    @Test
    fun `a completion screen's own bar has nothing to hold`() {
        val received = BroadcastFixtures.session().let { broadcastReceivedState(it, "p", formatters) }

        assertFalse(received.allAction.enabled)
        assertEquals(TransferAllLabel.PAUSE_ALL, received.allAction.label)
    }

    @Test
    fun `the sender's completion opens on the reference's finished fan-out`() {
        val viewModel = BroadcastSentViewModel(formatters)

        assertEquals(3, viewModel.state.value.recipients.size)
        assertTrue(viewModel.state.value.result.fullySuccessful)
        assertNull("nothing is hidden from the reader", viewModel.state.value.recipients.firstOrNull { !it.clean })
    }

    // ---------------------------------------------------------------- helpers

    private fun senderViewModel(chosen: String): BroadcastSenderViewModel = BroadcastSenderViewModel(
        SavedStateHandle(mapOf(Routes.BROADCAST_CHOSEN_ARG to chosen)),
        formatters,
    )

    private fun senderState(chosen: String): BroadcastSenderUiState = senderViewModel(chosen).state.value
}
