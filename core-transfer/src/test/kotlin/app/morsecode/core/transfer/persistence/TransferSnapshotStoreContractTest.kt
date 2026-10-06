package app.morsecode.core.transfer.persistence

import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo
import app.morsecode.core.transfer.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A contract test for `TransferSnapshotStore`, run against a deterministic
 * in-memory adapter.
 *
 * The adapter is deliberately not the point. What is being asserted is that the
 * interface can be implemented with no Room annotation, no Android class, no
 * coroutine and no clock, and that the three atomicity rules the interface
 * documents actually hold: a transition and its durable effects land together, a
 * checkpoint is idempotent for a given offset, and a restore never promotes
 * optimistic bytes to confirmed ones.
 */
class TransferSnapshotStoreContractTest {

    @Test fun `a saved session is restored with all of its deliveries`() {
        val store = InMemorySnapshotStore()
        val a = Tf.pausedLocally(transferId = TransferId("tr-a"), confirmedBytes = 4_096L)
        val b = Tf.queued(transferId = TransferId("tr-b"))
        store.saveTransition(null, a, emptyList())
        store.saveTransition(null, b, emptyList())

        val session = store.loadedSession(Tf.sessionId)
        assertTrue(session != null)
        assertEquals(2, session!!.transfers.size)
        assertEquals(setOf(TransferId("tr-a"), TransferId("tr-b")), session.transfers.map { it.transferId }.toSet())
        assertEquals(TransferSnapshotCodec.VERSION, session.version)
    }

    @Test fun `an absent session and transfer have an explicit missing result`() {
        assertEquals(StoreReadResult.Missing, InMemorySnapshotStore().loadSession(SessionId("nope")))
        assertEquals(StoreReadResult.Missing, InMemorySnapshotStore().loadTransfer(TransferId("nope")))
    }

    @Test fun `a saved transition is restored as an equal snapshot`() {
        val store = InMemorySnapshotStore()
        val snapshot = Tf.pausedLocally(confirmedBytes = 4_096L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, snapshot, emptyList()))
        val restored = store.loadedTransfer(snapshot.transferId)
        assertTrue(restored != null)
        assertEquals(snapshot.copy(failure = null), restored!!.copy(failure = null))
        assertEquals(4_096L, restored.confirmedBytes)
    }

    @Test fun `a transition and its durable effects land in one transaction`() {
        val store = InMemorySnapshotStore()
        val before = Tf.sending()
        val after = before + Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        val confirmed = after + Tf.chunkAck(
            offset = 0L,
            length = Tf.chunk.value,
            confirmedOffset = Tf.chunk.value.toLong(),
        )
        val durable = listOf(
            TransferEffect.PersistSnapshot(
                transferId = confirmed.transferId,
                snapshotVersion = confirmed.snapshotVersion,
            ),
            TransferEffect.PersistConfirmedOffset(
                transferId = confirmed.transferId,
                confirmedOffset = Tf.chunk.value.toLong(),
            ),
        )
        assertEquals(StoreResult.Ok, store.saveTransition(before, confirmed, durable))
        // Either both the state and the checkpoint are visible, or neither is:
        // there is no observable moment where the state has advanced and the
        // offset has not.
        assertEquals(Tf.chunk.value.toLong(), store.loadedTransfer(confirmed.transferId)!!.confirmedBytes)
        assertEquals(
            Tf.chunk.value.toLong(),
            store.checkpointFor(confirmed.transferId)?.value,
        )
    }

    @Test fun `saveCheckpoint is idempotent for a given offset`() {
        val store = InMemorySnapshotStore()
        val snapshot = Tf.receiving()
        store.saveTransition(null, snapshot, emptyList())
        val offset = ConfirmedOffset(4_096L)
        assertEquals(StoreResult.Ok, store.saveCheckpoint(snapshot.transferId, offset))
        repeat(5) {
            assertEquals(StoreResult.Ok, store.saveCheckpoint(snapshot.transferId, offset))
        }
        assertEquals(offset, store.checkpointFor(snapshot.transferId))
    }

    @Test fun `a checkpoint never moves backwards`() {
        val store = InMemorySnapshotStore()
        val snapshot = Tf.receiving()
        store.saveTransition(null, snapshot, emptyList())
        store.saveCheckpoint(snapshot.transferId, ConfirmedOffset(8_192L))
        val result = store.saveCheckpoint(snapshot.transferId, ConfirmedOffset(4_096L))
        // The adapter refuses to regress rather than silently accepting it, so a
        // late acknowledgement cannot rewind a resumed transfer.
        assertTrue(result is StoreResult.Failed)
        assertEquals(8_192L, store.checkpointFor(snapshot.transferId)!!.value)
    }

    @Test fun `a verification result is stored and restored`() {
        val store = InMemorySnapshotStore()
        val verifying = Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed()
        store.saveTransition(null, verifying, emptyList())
        val info = verifying.verification
        assertTrue(info != null)
        assertEquals(StoreResult.Ok, store.saveVerificationResult(verifying.transferId, info!!))
        assertEquals(info, store.verificationFor(verifying.transferId))
    }

    @Test fun `listResumable excludes deliveries that have finished`() {
        val store = InMemorySnapshotStore()
        store.saveTransition(null, Tf.pausedLocally(transferId = TransferId("tr-paused")), emptyList())
        store.saveTransition(null, Tf.queued(transferId = TransferId("tr-queued")), emptyList())
        store.saveTransition(null, Tf.failedRetryable(transferId = TransferId("tr-retry")), emptyList())
        store.saveTransition(null, Tf.completed(transferId = TransferId("tr-done")), emptyList())
        store.saveTransition(null, Tf.failedFinally(transferId = TransferId("tr-final")), emptyList())
        store.saveTransition(
            null,
            Tf.queued(transferId = TransferId("tr-cancelled")) +
                Tf.cancelLocally(transferId = TransferId("tr-cancelled")),
            emptyList(),
        )
        store.saveTransition(
            null,
            Tf.queued(transferId = TransferId("tr-skipped")) +
                Tf.skip(transferId = TransferId("tr-skipped")),
            emptyList(),
        )
        assertEquals(
            setOf(TransferId("tr-paused"), TransferId("tr-queued"), TransferId("tr-retry")),
            store.resumable(Tf.sessionId).map { it.transferId }.toSet(),
        )
    }

    @Test fun `listResumable is scoped to one session`() {
        val store = InMemorySnapshotStore()
        store.saveTransition(null, Tf.pausedLocally(transferId = TransferId("tr-mine")), emptyList())
        store.saveTransition(
            null,
            Tf.pausedLocally(
                transferId = TransferId("tr-other"),
                sessionId = SessionId("sess-other"),
            ),
            emptyList(),
        )
        assertEquals(
            listOf(TransferId("tr-mine")),
            store.resumable(Tf.sessionId).map { it.transferId },
        )
        assertEquals(
            listOf(TransferId("tr-other")),
            store.resumable(SessionId("sess-other")).map { it.transferId },
        )
    }

    @Test fun `a terminal delivery is dropped according to the retention policy`() {
        val store = InMemorySnapshotStore()
        val done = Tf.completed(transferId = TransferId("tr-done"))
        val failed = Tf.failedFinally(transferId = TransferId("tr-failed"))
        store.saveTransition(null, done, emptyList())
        store.saveTransition(null, failed, emptyList())

        assertEquals(StoreResult.Ok, store.removeTerminal(done.transferId, RetentionPolicy.KEEP_FOR_HISTORY))
        assertTrue(store.loadedTransfer(done.transferId) != null)

        assertEquals(StoreResult.Ok, store.removeTerminal(failed.transferId, RetentionPolicy.DISCARD_IMMEDIATELY))
        assertNull(store.loadedTransfer(failed.transferId))
    }

    @Test fun `a non terminal delivery is never dropped`() {
        val store = InMemorySnapshotStore()
        val snapshot = Tf.pausedLocally()
        store.saveTransition(null, snapshot, emptyList())
        val result = store.removeTerminal(snapshot.transferId, RetentionPolicy.DISCARD_IMMEDIATELY)
        assertTrue(result is StoreResult.Failed)
        assertTrue(store.loadedTransfer(snapshot.transferId) != null)
    }

    @Test fun `every retention policy round trips through its id`() {
        for (policy in RetentionPolicy.entries) {
            assertEquals(policy, RetentionPolicy.fromId(policy.id))
        }
        assertEquals(RetentionPolicy.KEEP_FOR_HISTORY, RetentionPolicy.fromId("nonsense"))
    }

    // --- a restart: the whole point of persisting -----------------------------------------------

    @Test fun `a restart resumes from the persisted snapshot, not from zero`() {
        val store = InMemorySnapshotStore()
        val paused = Tf.pausedLocally(confirmedBytes = 4_096L)
        store.saveTransition(null, paused, emptyList())

        // Simulate a process death: serialise everything, throw the objects away
        // and read them back through the codec.
        val durable = store.exportAll()
        val restarted = InMemorySnapshotStore()
        restarted.importAll(durable)

        val restored = restarted.loadedTransfer(paused.transferId)
        assertTrue(restored != null)
        assertEquals(4_096L, restored!!.confirmedBytes)
        assertEquals(4_096L, restored.optimisticBytes)
        assertEquals(
            listOf(paused.transferId),
            restarted.resumable(Tf.sessionId).map { it.transferId },
        )
    }

    @Test fun `a restart does not promote optimistic bytes to confirmed bytes`() {
        val store = InMemorySnapshotStore()
        val inFlight = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkSent(offset = Tf.chunk.value.toLong(), length = Tf.chunk.value)
        assertEquals(0L, inFlight.confirmedBytes)
        assertEquals(2L * Tf.chunk.value, inFlight.optimisticBytes)
        store.saveTransition(null, inFlight, emptyList())

        val restarted = InMemorySnapshotStore()
        restarted.importAll(store.exportAll())
        val restored = restarted.loadedTransfer(inFlight.transferId)!!
        assertEquals(0L, restored.confirmedBytes)
        assertEquals(2L * Tf.chunk.value, restored.optimisticBytes)
        assertFalse(restored.allBytesConfirmed)
    }

    @Test fun `a corrupt row is reported rather than crashing a restart`() {
        val store = InMemorySnapshotStore()
        store.saveTransition(null, Tf.queued(), emptyList())
        val corrupted = store.exportAll().map { row ->
            row.replace("state=queued", "state=dancing")
        }
        val restarted = InMemorySnapshotStore()
        val rejected = restarted.importAllReporting(corrupted)
        assertEquals(1, rejected.size)
        assertTrue(rejected.single().error is TransferError.PersistedSnapshotInvalid)
        // The bad row is skipped, not fatal: the rest of the queue still loads.
        assertTrue(restarted.loadedTransfer(Tf.transferId) == null)
        assertTrue(restarted.resumable(Tf.sessionId).isEmpty())
    }

    @Test fun `an empty store resumes nothing and reports no error`() {
        val store = InMemorySnapshotStore()
        assertTrue(store.resumable(Tf.sessionId).isEmpty())
        assertTrue(store.exportAll().isEmpty())
    }
}

private fun InMemorySnapshotStore.loadedSession(sessionId: SessionId): SessionSnapshot? =
    when (val result = loadSession(sessionId)) {
        StoreReadResult.Missing -> null
        is StoreReadResult.Found -> result.value
        is StoreReadResult.Failed -> throw AssertionError(result.error.describe())
    }

private fun InMemorySnapshotStore.loadedTransfer(transferId: TransferId): TransferSnapshot? =
    when (val result = loadTransfer(transferId)) {
        StoreReadResult.Missing -> null
        is StoreReadResult.Found -> result.value
        is StoreReadResult.Failed -> throw AssertionError(result.error.describe())
    }

private fun InMemorySnapshotStore.resumable(sessionId: SessionId): List<TransferSnapshot> =
    when (val result = listResumable(sessionId)) {
        StoreReadResult.Missing -> emptyList()
        is StoreReadResult.Found -> result.value
        is StoreReadResult.Failed -> throw AssertionError(result.error.describe())
    }

/*
 * A deterministic in-memory adapter for the contract above.
 *
 * It stores *serialised* rows, exactly as a Room adapter would, so every test
 * above goes through `TransferSnapshotCodec` on the way in and out. Nothing here
 * reads a clock, spawns a coroutine or touches the file system; the only state is
 * a linked map, so iteration order is insertion order and every assertion above is
 * reproducible.
 */
private class InMemorySnapshotStore : TransferSnapshotStore {

    private val rows = LinkedHashMap<TransferId, String>()
    private val checkpoints = HashMap<TransferId, ConfirmedOffset>()
    private val verifications = HashMap<TransferId, VerificationInfo>()

    override fun loadSession(sessionId: SessionId): StoreReadResult<SessionSnapshot> {
        val transfers = mutableListOf<TransferSnapshot>()
        for (row in rows.values) {
            when (val decoded = TransferSnapshotCodec.deserialize(row)) {
                is SnapshotDecodeResult.Invalid -> return StoreReadResult.Failed(decoded.error)
                is SnapshotDecodeResult.Success -> if (decoded.snapshot.sessionId == sessionId) {
                    transfers += decoded.snapshot
                }
            }
        }
        if (transfers.isEmpty()) return StoreReadResult.Missing
        return StoreReadResult.Found(SessionSnapshot(sessionId = sessionId, transfers = transfers))
    }

    override fun loadTransfer(transferId: TransferId): StoreReadResult<TransferSnapshot> {
        val row = rows[transferId] ?: return StoreReadResult.Missing
        return when (val decoded = TransferSnapshotCodec.deserialize(row)) {
            is SnapshotDecodeResult.Invalid -> StoreReadResult.Failed(decoded.error)
            is SnapshotDecodeResult.Success -> if (decoded.snapshot.transferId == transferId) {
                StoreReadResult.Found(decoded.snapshot)
            } else {
                StoreReadResult.Failed(TransferError.PersistedSnapshotInvalid("transfer_identity_mismatch"))
            }
        }
    }

    override fun saveTransition(
        previous: TransferSnapshot?,
        current: TransferSnapshot,
        effects: List<TransferEffect>,
    ): StoreResult {
        // One transaction: the row and every durable effect it implies are
        // committed together, so a failure part way through leaves the old row
        // untouched rather than a new state over an old offset.
        val committed = LinkedHashMap(rows)
        committed[current.transferId] = TransferSnapshotCodec.serialize(current)
        val committedCheckpoints = HashMap(checkpoints)
        val committedVerifications = HashMap(verifications)
        for (effect in effects) {
            when (effect) {
                is TransferEffect.PersistConfirmedOffset -> {
                    val offset = ConfirmedOffset(effect.confirmedOffset)
                    val existing = committedCheckpoints[effect.transferId]
                    if (existing == null || offset.value > existing.value) {
                        committedCheckpoints[effect.transferId] = offset
                    }
                }
                is TransferEffect.PersistSnapshot -> {
                    // The effect names the version that was committed; the row
                    // itself is written by the caller, so there is nothing more to
                    // store here beyond remembering it happened.
                    val row = committed[effect.transferId]
                    assertTrue(row != null)
                }
                else -> Unit // non-durable effects are someone else's business
            }
        }
        rows.clear()
        rows.putAll(committed)
        checkpoints.clear()
        checkpoints.putAll(committedCheckpoints)
        verifications.clear()
        verifications.putAll(committedVerifications)
        return StoreResult.Ok
    }

    override fun saveCheckpoint(transferId: TransferId, confirmedOffset: ConfirmedOffset): StoreResult {
        val existing = checkpoints[transferId]
        if (existing != null && confirmedOffset.value < existing.value) {
            return StoreResult.Failed(
                TransferError.UnexpectedOffset(
                    expected = existing.value,
                    actual = confirmedOffset.value,
                ),
            )
        }
        checkpoints[transferId] = confirmedOffset
        return StoreResult.Ok
    }

    override fun saveVerificationResult(
        transferId: TransferId,
        result: VerificationInfo,
    ): StoreResult {
        verifications[transferId] = result
        return StoreResult.Ok
    }

    override fun listResumable(sessionId: SessionId): StoreReadResult<List<TransferSnapshot>> {
        val decoded = mutableListOf<TransferSnapshot>()
        for (row in rows.values) {
            when (val result = TransferSnapshotCodec.deserialize(row)) {
                is SnapshotDecodeResult.Invalid -> return StoreReadResult.Failed(result.error)
                is SnapshotDecodeResult.Success -> decoded += result.snapshot
            }
        }
        val inSession = decoded.filter { it.sessionId == sessionId }
        if (inSession.isEmpty()) return StoreReadResult.Missing
        return StoreReadResult.Found(inSession.filterNot { it.isTerminal })
    }

    override fun removeTerminal(
        transferId: TransferId,
        policy: RetentionPolicy,
    ): StoreResult {
        val snapshot = when (val loaded = loadTransfer(transferId)) {
            StoreReadResult.Missing -> return StoreResult.Failed(TransferError.PersistenceConflict("snapshot_missing"))
            is StoreReadResult.Failed -> return StoreResult.Failed(loaded.error)
            is StoreReadResult.Found -> loaded.value
        }
        if (!snapshot.isTerminal) {
            return StoreResult.Failed(TransferError.PersistenceConflict("terminal_required"))
        }
        if (policy == RetentionPolicy.DISCARD_IMMEDIATELY) {
            rows.remove(transferId)
            checkpoints.remove(transferId)
            verifications.remove(transferId)
        }
        return StoreResult.Ok
    }

    // --- test-only accessors ------------------------------------------------------

    fun checkpointFor(transferId: TransferId): ConfirmedOffset? = checkpoints[transferId]
    fun verificationFor(transferId: TransferId): VerificationInfo? = verifications[transferId]
    fun exportAll(): List<String> = rows.values.toList()

    fun importAll(serialized: List<String>) {
        importAllReporting(serialized)
    }

    fun importAllReporting(serialized: List<String>): List<SnapshotDecodeResult.Invalid> {
        val rejected = mutableListOf<SnapshotDecodeResult.Invalid>()
        for (row in serialized) {
            when (val result = TransferSnapshotCodec.deserialize(row)) {
                is SnapshotDecodeResult.Success -> rows[result.snapshot.transferId] = row
                is SnapshotDecodeResult.Invalid -> rejected += result
            }
        }
        return rejected
    }
}
