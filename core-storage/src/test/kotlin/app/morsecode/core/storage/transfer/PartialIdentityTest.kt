package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Partial identity and the commit lifecycle.
 *
 * The identity test is about independence from the file name: a rename must not
 * change identity, and two transfers of files with the same display name must not
 * share one. The lifecycle test is about the states that have to survive a
 * process death, because `COMMITTING` is the one where losing the record loses
 * the file.
 */
class PartialIdentityTest {

    private val transfer = TransferId("transfer-0001")
    private val other = TransferId("transfer-0002")

    @Test
    fun `identity is derived from the transfer and the strategy, not the name`() {
        val identity = PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED)
        assertEquals("saf_staged:transfer-0001", identity.value)
        // Nothing user-visible is in it.
        assertFalse(identity.value.contains("mp4"))
        assertFalse(identity.value.contains('/'))
    }

    @Test
    fun `the same transfer and strategy always reach the same identity`() {
        // This is what makes restoration possible without a lookup table: a new
        // process can recompute the identity and find the file.
        assertEquals(
            PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED),
            PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED),
        )
    }

    @Test
    fun `different transfers never share an identity even with the same name`() {
        // Two transfers of two different files called photo.jpg must not collide.
        assertFalse(
            PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED) ==
                PartialIdentity.of(other, DestinationStrategy.SAF_STAGED),
        )
    }

    @Test
    fun `the same transfer under a different strategy is a different partial`() {
        assertFalse(
            PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED) ==
                PartialIdentity.of(transfer, DestinationStrategy.SAF_DIRECT),
        )
    }

    @Test
    fun `identity survives a database round-trip`() {
        val identity = PartialIdentity.of(transfer, DestinationStrategy.MEDIA_STORE_PENDING)
        assertEquals(identity, PartialIdentity.fromRow(identity.value))
    }

    @Test
    fun `a blank row is read as absent rather than as an empty identity`() {
        assertNull(PartialIdentity.fromRow(null))
        assertNull(PartialIdentity.fromRow(""))
        assertNull(PartialIdentity.fromRow("   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank identity cannot be constructed`() {
        PartialIdentity("")
    }

    // --- commit lifecycle -------------------------------------------------

    @Test
    fun `a partial that is still receiving bytes is not ready to commit`() {
        val state = CommitState.NOT_READY
        assertTrue(state.ownsPartialFile)
        assertFalse(state.isTerminal)
        assertFalse(state.requiresRecovery)
    }

    @Test
    fun `a crash during the final move is a state that must survive a restart`() {
        // The state exists precisely so that an interruption between
        // "verified" and "in place" is resumable instead of becoming a missing
        // file with no record that it was ever there.
        assertTrue(CommitState.COMMITTING.requiresRecovery)
        assertTrue(CommitState.COMMITTING.ownsPartialFile)
        assertFalse(CommitState.COMMITTING.isTerminal)
    }

    @Test
    fun `a failed commit stays visible and can be retried`() {
        assertTrue(CommitState.COMMIT_FAILED.requiresRecovery)
        assertTrue(CommitState.COMMIT_FAILED.ownsPartialFile)
        assertTrue(CommitState.allows(CommitState.COMMIT_FAILED, CommitState.COMMITTING))
        assertTrue(CommitState.allows(CommitState.COMMIT_FAILED, CommitState.ABANDONED))
    }

    @Test
    fun `terminal states admit no further transition`() {
        assertTrue(CommitState.COMMITTED.isTerminal)
        assertTrue(CommitState.ABANDONED.isTerminal)
        assertTrue(CommitState.next(CommitState.COMMITTED).isEmpty())
        assertTrue(CommitState.next(CommitState.ABANDONED).isEmpty())
    }

    @Test
    fun `a committed partial no longer owns a file on storage`() {
        assertFalse(CommitState.COMMITTED.ownsPartialFile)
        assertFalse(CommitState.ABANDONED.ownsPartialFile)
    }

    @Test
    fun `the happy path is the only route to committed`() {
        val path = listOf(
            CommitState.NOT_READY,
            CommitState.READY_TO_COMMIT,
            CommitState.COMMITTING,
            CommitState.COMMITTED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue("$from -> $to must be legal", CommitState.allows(from, to))
        }
    }

    @Test
    fun `a partial cannot jump straight from receiving bytes to committed`() {
        assertFalse(CommitState.allows(CommitState.NOT_READY, CommitState.COMMITTED))
        assertFalse(CommitState.allows(CommitState.NOT_READY, CommitState.COMMITTING))
    }

    @Test
    fun `a committed file can never go back to being a partial`() {
        CommitState.entries.forEach { to ->
            assertFalse(
                "$to must not be reachable from COMMITTED",
                CommitState.allows(CommitState.COMMITTED, to),
            )
        }
    }

    @Test
    fun `states have distinct ids and round-trip`() {
        assertEquals(6, CommitState.entries.size)
        assertEquals(6, CommitState.entries.map { it.id }.distinct().size)
        CommitState.entries.forEach { assertEquals(it, CommitState.fromId(it.id)) }
        assertEquals(CommitState.NOT_READY, CommitState.fromId("nonsense"))
    }
}
