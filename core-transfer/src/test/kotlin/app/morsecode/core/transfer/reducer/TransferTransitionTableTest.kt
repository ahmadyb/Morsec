package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table is total, terminal where it must be, and honest about how
 * it differs from core-model's UI-facing table.
 */
class TransferTransitionTableTest {

    @Test fun `all twelve states are declared`() {
        assertEquals(12, TransferTransitionTable.states.size)
        assertEquals(
            listOf(
                "QUEUED", "NEGOTIATING", "SENDING", "RECEIVING", "PAUSED_LOCAL",
                "PAUSED_REMOTE", "VERIFYING", "COMPLETED", "FAILED_RETRYABLE",
                "FAILED_FINAL", "CANCELLED", "SKIPPED",
            ),
            TransferTransitionTable.states.map { it.name },
        )
    }

    @Test fun `the table covers every state exactly once`() {
        assertEquals(
            TransferTransitionTable.states.toSet(),
            TransferTransitionTable.allowed.keys,
        )
    }

    @Test fun `completed cancelled skipped and failed final are terminal`() {
        for (state in listOf(
            TransferState.COMPLETED,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
            TransferState.FAILED_FINAL,
        )) {
            assertTrue(
                "$state must have no outgoing edges",
                TransferTransitionTable.allowed[state].isNullOrEmpty(),
            )
        }
    }

    @Test fun `failed retryable can reach the queue again but nothing else terminal`() {
        val targets = TransferTransitionTable.allowed[TransferState.FAILED_RETRYABLE]!!
        assertTrue(targets.contains(TransferState.QUEUED))
        assertFalse(targets.contains(TransferState.COMPLETED))
        assertFalse(targets.contains(TransferState.VERIFYING))
    }

    @Test fun `nothing moves straight to completed except verifying and the already verified edge`() {
        for (state in TransferTransitionTable.states) {
            val canComplete = TransferTransitionTable.canMove(state, TransferState.COMPLETED)
            if (state == TransferState.VERIFYING || state == TransferState.NEGOTIATING) {
                assertTrue("$state must be able to reach COMPLETED", canComplete)
            } else {
                assertFalse("$state must not reach COMPLETED directly", canComplete)
            }
        }
    }

    @Test fun `paused deliveries resume through negotiation`() {
        assertTrue(
            TransferTransitionTable.canMove(TransferState.PAUSED_LOCAL, TransferState.NEGOTIATING),
        )
        assertTrue(
            TransferTransitionTable.canMove(TransferState.PAUSED_REMOTE, TransferState.NEGOTIATING),
        )
        assertFalse(
            TransferTransitionTable.canMove(TransferState.PAUSED_LOCAL, TransferState.SENDING),
        )
        assertFalse(
            TransferTransitionTable.canMove(TransferState.PAUSED_REMOTE, TransferState.RECEIVING),
        )
    }

    @Test fun `the divergence from the core model table is exactly the documented list`() {
        val divergence = TransferTransitionTable.divergenceFromCoreModel().toSet()
        assertEquals(
            setOf(
                // Paused deliveries re-negotiate instead of jumping back into
                // SENDING or RECEIVING.
                TransferState.PAUSED_LOCAL to TransferState.SENDING,
                TransferState.PAUSED_LOCAL to TransferState.RECEIVING,
                TransferState.PAUSED_LOCAL to TransferState.FAILED_FINAL,
                TransferState.PAUSED_REMOTE to TransferState.SENDING,
                TransferState.PAUSED_REMOTE to TransferState.RECEIVING,
                TransferState.PAUSED_REMOTE to TransferState.FAILED_RETRYABLE,
                TransferState.PAUSED_REMOTE to TransferState.FAILED_FINAL,
                // Retry re-queues first so the scheduler decides when the
                // attempt happens, instead of re-entering negotiation inline.
                TransferState.FAILED_RETRYABLE to TransferState.NEGOTIATING,
                // FAILED_FINAL is terminal here; core-model allows it to become
                // CANCELLED for the "dismiss row" gesture, which is a UI action
                // rather than a transfer transition.
                TransferState.FAILED_FINAL to TransferState.CANCELLED,
            ),
            divergence,
        )
    }

    @Test fun `additions beyond the core model table are exactly the documented list`() {
        assertEquals(
            setOf(
                // Both-sides pause has to be representable.
                TransferState.PAUSED_LOCAL to TransferState.PAUSED_REMOTE,
                TransferState.PAUSED_REMOTE to TransferState.PAUSED_LOCAL,
                // A peer pause is cleared by re-negotiating the offset, not by
                // pushing bytes straight back into the socket.
                TransferState.PAUSED_REMOTE to TransferState.NEGOTIATING,
                // A queued delivery can be ended by a final error (a revoked
                // permission, for instance) without ever occupying a slot.
                TransferState.QUEUED to TransferState.FAILED_FINAL,
                // The receiving side reports a verified copy; no bytes move.
                TransferState.NEGOTIATING to TransferState.COMPLETED,
            ),
            TransferTransitionTable.additionsBeyondCoreModel().toSet(),
        )
    }

    @Test fun `the markdown table renders every state`() {
        val markdown = TransferTransitionTable.toMarkdown()
        for (state in TransferTransitionTable.states) {
            assertTrue(markdown.contains("`${state.name}`"))
        }
        assertTrue(markdown.contains("*(terminal)*"))
        // A header, a separator and twelve rows.
        assertEquals(14, markdown.trim().lines().size)
    }

    @Test fun `every legal edge is distinct and both ends are real states`() {
        val edges = TransferTransitionTable.legalEdges()
        assertEquals(edges.toSet().size, edges.size)
        for ((from, to) in edges) {
            assertTrue(from in TransferTransitionTable.states)
            assertTrue(to in TransferTransitionTable.states)
            assertTrue(from != to)
        }
    }
}
