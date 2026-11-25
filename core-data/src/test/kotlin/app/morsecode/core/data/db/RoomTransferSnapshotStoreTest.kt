package app.morsecode.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo
import app.morsecode.core.transfer.persistence.StoreReadResult
import app.morsecode.core.transfer.persistence.StoreResult
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.protocol.ResumeDecision
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.resume.ReceiverResumeState
import app.morsecode.core.transfer.resume.ReceiverVerificationState
import app.morsecode.core.transfer.resume.ResumeNegotiator
import app.morsecode.core.transfer.resume.ResumeProposal
import app.morsecode.core.transfer.reducer.TransitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTransferSnapshotStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<MorseDatabase>()
    private val databaseNames = mutableListOf<String>()

    @After
    fun closeDatabases() {
        databases.forEach(MorseDatabase::close)
        databaseNames.forEach { name -> context.deleteDatabase(name) }
    }

    @Test
    fun `snapshot and confirmed checkpoint survive database reopen`() {
        val name = databaseName()
        val firstDb = openDatabase(name)
        val store = RoomTransferSnapshotStore(firstDb, Dispatchers.IO)
        val initial = receivingSnapshot()
        assertEquals(StoreResult.Ok, store.saveTransition(null, initial, emptyList()))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(initial.transferId, ConfirmedOffset(4_096L)))
        firstDb.close()
        databases.remove(firstDb)

        val reopened = openDatabase(name)
        val restored = RoomTransferSnapshotStore(reopened, Dispatchers.IO)
            .loadTransfer(initial.transferId)
        assertTrue(restored is StoreReadResult.Found)
        val snapshot = (restored as StoreReadResult.Found).value
        assertEquals(initial.copy(confirmedBytes = 4_096L), snapshot)
    }

    @Test
    fun `staged receiver checkpoint survives reopen before acknowledgement`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val before = receivingSnapshot().copy(optimisticBytes = 0L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
        ) as TransitionResult.Accepted
        assertEquals(0L, staged.current.confirmedBytes)
        assertEquals(4_096L, staged.current.optimisticBytes)
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        database.close()
        databases.remove(database)

        val reopened = openDatabase(name)
        val restoredStore = RoomTransferSnapshotStore(reopened, Dispatchers.IO)
        val restored = (restoredStore.loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(4_096L, restored.confirmedBytes)
        assertEquals(4_096L, restored.optimisticBytes)
        assertEquals(1L, restored.nextExpectedSequence.value)
        assertEquals(ResumeDecision.ResumeAt(4_096L), negotiateRestored(restored))
        // saveCheckpoint does not advance this field; it cannot prove an ACK was sent.
        assertEquals(null, restored.lastAcknowledgedSequence)
        assertEquals(0L, staged.current.confirmedBytes)
        // The old in-memory pending candidate is gone after a crash. Replaying
        // its success against the restored snapshot is refused, not re-ACKed.
        assertTrue(
            TransferReducer.apply(
                restored,
                TransferEvent.ConfirmedOffsetPersisted(before.sessionId, before.transferId, before.recipientId, 4_096L),
            ) is TransitionResult.Rejected,
        )
    }

    @Test
    fun `short chunk checkpoint survives reopen at its exact unaligned offset`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val before = receivingSnapshot().copy(optimisticBytes = 0L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 2_048, 0L),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(2_048L)))
        database.close()
        databases.remove(database)
        val restored = (RoomTransferSnapshotStore(openDatabase(name), Dispatchers.IO)
            .loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(2_048L, restored.confirmedBytes)
        assertEquals(ResumeDecision.ResumeAt(2_048L), negotiateRestored(restored))
        assertEquals(0L, restored.nextExpectedSequence.value)
        assertEquals(null, restored.lastAcknowledgedSequence)
    }

    @Test
    fun `sender persists a recovered peer checkpoint as a snapshot before announcing resume`() {
        val store = RoomTransferSnapshotStore(openDatabase(), Dispatchers.IO)
        val before = receivingSnapshot().copy(
            direction = SessionDirection.OUTBOUND,
            state = TransferState.NEGOTIATING,
            optimisticBytes = 0L,
        )
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val resumed = TransferReducer.apply(
            before,
            TransferCommand.ApplyResumeDecision(
                before.sessionId, before.transferId, before.recipientId, ResumeDecision.ResumeAt(2_048L),
            ),
        ) as TransitionResult.Accepted
        assertEquals(2_048L, resumed.current.confirmedBytes)
        assertEquals(null, resumed.current.lastAcknowledgedSequence)
        assertTrue(resumed.effects[0] is TransferEffect.PersistSnapshot)
        assertTrue(resumed.effects[1] is TransferEffect.SendResume)
        assertEquals(StoreResult.Ok, store.saveTransition(before, resumed.current, resumed.effects.take(1)))
        assertEquals(StoreReadResult.Found(resumed.current), store.loadTransfer(before.transferId))
    }

    @Test
    fun `live checkpoint promotion uses the durable row as the CAS predecessor`() {
        val store = RoomTransferSnapshotStore(openDatabase(), Dispatchers.IO)
        val before = receivingSnapshot().copy(optimisticBytes = 0L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        val durable = (store.loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(staged.current.snapshotVersion, durable.snapshotVersion)
        assertEquals(4_096L, durable.confirmedBytes)
        val accepted = TransferReducer.apply(
            staged.current,
            TransferEvent.ConfirmedOffsetPersisted(before.sessionId, before.transferId, before.recipientId, 4_096L),
        ) as TransitionResult.Accepted
        assertTrue(store.saveTransition(staged.current, accepted.current, accepted.effects.take(1)) is StoreResult.Failed)
        assertEquals(StoreResult.Ok, store.saveTransition(durable, accepted.current, accepted.effects.take(1)))
        assertEquals(StoreReadResult.Found(accepted.current), store.loadTransfer(before.transferId))
        assertTrue(accepted.effects[1] is TransferEffect.SendAcknowledgement)
    }

    @Test
    fun `a final checkpoint reopened before ACK can start local verification`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val before = receivingSnapshot().let { it.copy(descriptor = it.descriptor.copy(totalBytes = 4_096L), optimisticBytes = 0L) }
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        database.close()
        databases.remove(database)
        val restored = (RoomTransferSnapshotStore(openDatabase(name), Dispatchers.IO)
            .loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertTrue(restored.allBytesConfirmed)
        assertEquals(ResumeDecision.ResumeAt(4_096L), negotiateRestored(restored))
        val verifying = TransferReducer.apply(
            restored,
            TransferEvent.AllBytesConfirmed(before.sessionId, before.transferId, before.recipientId),
        ) as TransitionResult.Accepted
        assertEquals(TransferState.VERIFYING, verifying.current.state)
        assertTrue(verifying.effects.any { it is TransferEffect.BeginVerification })
        assertTrue(verifying.effects.none { it is TransferEffect.SendAcknowledgement })
    }

    @Test
    fun `a failed checkpoint leaves the durable floor unchanged and cannot authorize an ACK`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val before = receivingSnapshot().copy(optimisticBytes = 0L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        rejectSnapshotUpdates(database, before)
        assertTrue(store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)) is StoreResult.Failed)
        assertEquals(StoreReadResult.Found(staged.current), store.loadTransfer(before.transferId))
        val failed = TransferReducer.apply(
            staged.current,
            TransferEvent.ConfirmedOffsetPersistenceFailed(before.sessionId, before.transferId, before.recipientId, 4_096L),
        ) as TransitionResult.Accepted
        assertEquals(0L, failed.current.confirmedBytes)
        assertTrue(failed.current.failure is TransferError.PersistenceFailure)
        assertTrue(failed.effects.none { it is TransferEffect.SendAcknowledgement })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_contract_update")
        // The staged revision already exists; the failure revision is next.
        assertEquals(StoreResult.Ok, store.saveTransition(staged.current, failed.current, failed.effects.drop(1).take(1)))
        database.close()
        databases.remove(database)
        val restored = (RoomTransferSnapshotStore(openDatabase(name), Dispatchers.IO)
            .loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(0L, restored.confirmedBytes)
        assertEquals(0L, restored.optimisticBytes)
        assertEquals("persistence_io", restored.failure?.code)
    }

    @Test
    fun `failed final snapshot persistence cannot run its later ACK`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val before = receivingSnapshot().copy(optimisticBytes = 0L)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val staged = TransferReducer.apply(
            before,
            TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, staged.current, staged.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        val durable = (store.loadTransfer(before.transferId) as StoreReadResult.Found).value
        val confirmed = TransferReducer.apply(
            staged.current,
            TransferEvent.ConfirmedOffsetPersisted(before.sessionId, before.transferId, before.recipientId, 4_096L),
        ) as TransitionResult.Accepted
        rejectSnapshotUpdates(database, before)
        assertTrue(store.saveTransition(durable, confirmed.current, confirmed.effects.take(1)) is StoreResult.Failed)
        assertTrue(confirmed.effects[1] is TransferEffect.SendAcknowledgement)
        // The ordered program stops on the failed first effect; the surviving
        // row retains the checkpoint, not a claim that the ACK was delivered.
        val restored = (store.loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(4_096L, restored.confirmedBytes)
        assertEquals(null, restored.lastAcknowledgedSequence)
    }

    @Test
    fun `failed commit preflight leaves the unverified row and must not publish`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val digest = requireNotNull(Sha256Digest.fromHex("a".repeat(64)))
        val before = receivingSnapshot().let { base ->
            base.copy(
                descriptor = base.descriptor.copy(expectedSha256 = digest),
                state = TransferState.VERIFYING,
                confirmedBytes = base.totalBytes,
                optimisticBytes = base.totalBytes,
                verification = VerificationInfo(digest, null, base.snapshotVersion),
            )
        }
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val verified = TransferReducer.apply(
            before,
            TransferEvent.VerificationSucceeded(before.sessionId, before.transferId, before.recipientId, digest),
        ) as TransitionResult.Accepted
        rejectSnapshotUpdates(database, before)
        assertTrue(store.saveTransition(before, verified.current, emptyList()) is StoreResult.Failed)
        assertEquals(StoreReadResult.Found(before), store.loadTransfer(before.transferId))
        val commit = verified.effects.single() as TransferEffect.CommitVerifiedDestination
        assertEquals(verified.current.snapshotVersion, commit.verifiedSnapshotVersion)
        // No publisher is joined in this test or in production by this slice.
    }

    @Test
    fun `a post publication save failure retains provisional state for reconciliation`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val digest = requireNotNull(Sha256Digest.fromHex("a".repeat(64)))
        val before = receivingSnapshot().let { base ->
            base.copy(
                descriptor = base.descriptor.copy(expectedSha256 = digest),
                state = TransferState.VERIFYING,
                confirmedBytes = base.totalBytes,
                optimisticBytes = base.totalBytes,
                verification = VerificationInfo(digest, null, base.snapshotVersion),
            )
        }
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val verified = TransferReducer.apply(
            before,
            TransferEvent.VerificationSucceeded(before.sessionId, before.transferId, before.recipientId, digest),
        ) as TransitionResult.Accepted
        assertEquals(StoreResult.Ok, store.saveTransition(before, verified.current, emptyList()))
        // Simulate a trusted report that publication succeeded, without joining a publisher.
        val committed = TransferReducer.apply(
            verified.current,
            TransferEvent.DestinationCommitSucceeded(before.sessionId, before.transferId, before.recipientId),
        ) as TransitionResult.Accepted
        rejectSnapshotUpdates(database, before)
        assertTrue(store.saveTransition(verified.current, committed.current, committed.effects.drop(1).take(1)) is StoreResult.Failed)
        assertTrue(committed.effects[2] is TransferEffect.SendVerificationResult)
        database.close()
        databases.remove(database)
        val restored = (RoomTransferSnapshotStore(openDatabase(name), Dispatchers.IO)
            .loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(verified.current, restored)
        assertTrue(
            TransferReducer.apply(
                restored,
                TransferCommand.CancelLocally(before.sessionId, before.transferId, before.recipientId),
            ) is TransitionResult.Rejected,
        )
        assertTrue(
            TransferReducer.apply(
                restored,
                TransferCommand.Retry(before.sessionId, before.transferId, before.recipientId),
            ) is TransitionResult.Rejected,
        )
    }

    @Test
    fun `provisional verified and completed revisions use the existing columns`() {
        val name = databaseName()
        val database = openDatabase(name)
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val digest = requireNotNull(Sha256Digest.fromHex("a".repeat(64)))
        val before = receivingSnapshot().let { base ->
            base.copy(
                descriptor = base.descriptor.copy(expectedSha256 = digest),
                state = TransferState.VERIFYING,
                confirmedBytes = base.totalBytes,
                optimisticBytes = base.totalBytes,
                verification = VerificationInfo(digest, null, base.snapshotVersion),
            )
        }
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        val verified = TransferReducer.apply(
            before,
            TransferEvent.VerificationSucceeded(before.sessionId, before.transferId, before.recipientId, digest),
        ) as TransitionResult.Accepted
        assertEquals(TransferState.VERIFYING, verified.current.state)
        assertEquals(
            listOf(TransferEffect.CommitVerifiedDestination(before.transferId, verified.current.snapshotVersion)),
            verified.effects,
        )
        assertEquals(StoreReadResult.Found(before), store.loadTransfer(before.transferId))

        // The commit effect's required preflight save happens before publication.
        assertEquals(StoreResult.Ok, store.saveTransition(before, verified.current, emptyList()))
        database.close()
        databases.remove(database)
        val recoveredDb = openDatabase(name)
        val recoveredStore = RoomTransferSnapshotStore(recoveredDb, Dispatchers.IO)
        val provisional = (recoveredStore.loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(verified.current, provisional)
        assertTrue(
            TransferReducer.apply(
                provisional,
                TransferCommand.CancelLocally(before.sessionId, before.transferId, before.recipientId),
            ) is TransitionResult.Rejected,
        )

        // Only a trusted, reconciled publication outcome may resume completion.
        val committed = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitSucceeded(before.sessionId, before.transferId, before.recipientId),
        ) as TransitionResult.Accepted
        assertEquals(TransferEffect.PersistSnapshot(before.transferId, provisional.snapshotVersion), committed.effects[0])
        // The first effect is already satisfied by the preflight row.
        assertEquals(StoreResult.Ok, recoveredStore.saveTransition(provisional, committed.current, committed.effects.drop(1).take(1)))
        recoveredDb.close()
        databases.remove(recoveredDb)
        val reopened = RoomTransferSnapshotStore(openDatabase(name), Dispatchers.IO)
        assertEquals(StoreReadResult.Found(committed.current), reopened.loadTransfer(before.transferId))
        assertTrue(committed.effects[2] is TransferEffect.SendVerificationResult)
        assertTrue(committed.effects[3] is TransferEffect.SendFileComplete)
    }

    @Test
    fun `typed descriptor digest failure and verification columns survive reopen`() {
        val name = databaseName()
        val firstDb = openDatabase(name)
        val snapshot = richSnapshot()
        val store = RoomTransferSnapshotStore(firstDb, Dispatchers.IO)
        assertEquals(StoreResult.Ok, store.saveTransition(null, snapshot, emptyList()))

        val stored = runBlocking { firstDb.transferSnapshotDao().find(snapshot.transferId.value)!! }
        assertEquals(1, stored.isFolderArchive)
        assertEquals(1, stored.remotePaused)
        assertEquals("[redacted]", stored.failureDetail)
        assertTrue(!stored.toString().contains(requireNotNull(snapshot.descriptor.expectedSha256).hex))

        firstDb.close()
        databases.remove(firstDb)
        val reopened = openDatabase(name)

        val failure = requireNotNull(snapshot.failure)
        val restoredSnapshot = snapshot.copy(
            failure = TransferError.restore(
                code = failure.code,
                detail = failure.detail,
                retryable = failure.retryable,
                origin = failure.origin,
                category = failure.category,
            ),
        )
        assertEquals(
            StoreReadResult.Found(restoredSnapshot),
            RoomTransferSnapshotStore(reopened, Dispatchers.IO).loadTransfer(snapshot.transferId),
        )
    }

    @Test
    fun `malformed stored boolean is rejected as an invalid row`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val initial = queuedSnapshot()
        assertEquals(StoreResult.Ok, store.saveTransition(null, initial, emptyList()))
        runBlocking {
            val row = database.transferSnapshotDao().find(initial.transferId.value)!!
            database.transferSnapshotDao().upsert(row.copy(remotePaused = 2))
        }

        val result = store.loadTransfer(initial.transferId)

        assertTrue(result is StoreReadResult.Failed)
        assertEquals(
            "invalid_snapshot_row_metadata",
            ((result as StoreReadResult.Failed).error as TransferError.PersistedSnapshotInvalid).reason,
        )
    }

    @Test
    fun `complete transition replacement rejects stale and gapped reducer revisions`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val initial = queuedSnapshot()
        assertEquals(StoreResult.Ok, store.saveTransition(null, initial, emptyList()))

        val second = initial.copy(snapshotVersion = initial.snapshotVersion + 1L)
        assertEquals(StoreResult.Ok, store.saveTransition(initial, second, emptyList()))
        // A retry of the exact replacement is idempotent even when its original
        // `previous` argument is now stale.
        assertEquals(StoreResult.Ok, store.saveTransition(initial, second, emptyList()))

        val gapped = second.copy(snapshotVersion = second.snapshotVersion + 2L)
        val gapResult = store.saveTransition(second, gapped, emptyList())
        assertTrue(gapResult is StoreResult.Failed)
        assertEquals(
            "snapshot_revision_gap",
            ((gapResult as StoreResult.Failed).error as TransferError.PersistenceConflict).reason,
        )

        val staleReplacement = second.copy(queueOrder = second.queueOrder + 1L)
        val staleResult = store.saveTransition(initial, staleReplacement, emptyList())
        assertTrue(staleResult is StoreResult.Failed)
        assertEquals(
            "stale_snapshot_revision",
            ((staleResult as StoreResult.Failed).error as TransferError.PersistenceConflict).reason,
        )
        assertEquals(
            StoreReadResult.Found(second),
            store.loadTransfer(second.transferId),
        )
    }

    @Test
    fun `failed replacement rolls back and leaves the old snapshot visible`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val initial = queuedSnapshot()
        assertEquals(StoreResult.Ok, store.saveTransition(null, initial, emptyList()))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_transfer_snapshot_update " +
                "BEFORE UPDATE ON transfer_snapshots WHEN OLD.transfer_id = '${initial.transferId.value}' " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )

        val next = initial.copy(snapshotVersion = initial.snapshotVersion + 1L)
        val failed = store.saveTransition(initial, next, emptyList())

        assertTrue(failed is StoreResult.Failed)
        assertEquals(StoreReadResult.Found(initial), store.loadTransfer(initial.transferId))
    }

    @Test
    fun `unknown persisted state token is a typed failure not a missing row`() {
        val database = openDatabase()
        val store = RoomTransferSnapshotStore(database, Dispatchers.IO)
        val initial = queuedSnapshot()
        assertEquals(StoreResult.Ok, store.saveTransition(null, initial, emptyList()))
        runBlocking {
            val row = database.transferSnapshotDao().find(initial.transferId.value)!!
            database.transferSnapshotDao().upsert(row.copy(snapshotState = "future_state"))
        }

        val result = store.loadTransfer(initial.transferId)

        assertTrue(result is StoreReadResult.Failed)
        val error = (result as StoreReadResult.Failed).error as TransferError.PersistedSnapshotInvalid
        assertEquals("unknown_transfer_state", error.reason)
    }

    private fun rejectSnapshotUpdates(database: MorseDatabase, snapshot: TransferSnapshot) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_contract_update " +
                "BEFORE UPDATE ON transfer_snapshots WHEN OLD.transfer_id = '${snapshot.transferId.value}' " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
    }

    private fun negotiateRestored(snapshot: TransferSnapshot): ResumeDecision {
        val descriptor = snapshot.descriptor
        return ResumeNegotiator.negotiate(
            ResumeProposal(
                protocolVersion = descriptor.protocolVersion,
                fileId = descriptor.fileId,
                totalBytes = descriptor.totalBytes,
                chunkSize = descriptor.chunkSize,
                expectedSha256 = descriptor.expectedSha256,
                relativePath = descriptor.relativePath,
                lastModifiedEpochMillis = descriptor.lastModifiedEpochMillis,
                descriptorFingerprint = descriptor.descriptorFingerprint,
            ),
            ReceiverResumeState(
                knownTransfer = true,
                fileId = descriptor.fileId,
                totalBytes = descriptor.totalBytes,
                confirmedBytes = snapshot.confirmedBytes,
                descriptorFingerprint = descriptor.descriptorFingerprint,
                chunkSize = descriptor.chunkSize,
                verification = ReceiverVerificationState.NOT_STARTED,
                expectedSha256 = descriptor.expectedSha256,
                lastModifiedEpochMillis = descriptor.lastModifiedEpochMillis,
            ),
        )
    }

    private fun queuedSnapshot(): TransferSnapshot = snapshot(TransferState.QUEUED, optimisticBytes = 0L)

    private fun receivingSnapshot(): TransferSnapshot =
        snapshot(TransferState.RECEIVING, optimisticBytes = 4_096L)

    private fun snapshot(state: TransferState, optimisticBytes: Long): TransferSnapshot {
        val transferId = TransferId("transfer-store")
        val sessionId = SessionId("session-store")
        val descriptor = TransferFileDescriptor(
            fileId = FileId("file-store"),
            displayName = "store.bin",
            relativePath = RelativeTransferPath("store/store.bin"),
            mimeType = "application/octet-stream",
            totalBytes = 8_192L,
            lastModifiedEpochMillis = null,
            isFolderArchive = false,
            expectedSha256 = null,
            chunkSize = ChunkSize(4_096),
            protocolVersion = ProtocolVersion.CURRENT,
        )
        return TransferSnapshot(
            transferId = transferId,
            sessionId = sessionId,
            batchId = BatchId("batch-store"),
            recipientId = null,
            direction = if (state == TransferState.RECEIVING) SessionDirection.INBOUND else SessionDirection.OUTBOUND,
            descriptor = descriptor,
            state = state,
            confirmedBytes = 0L,
            optimisticBytes = optimisticBytes,
            lastAcknowledgedSequence = null,
            retryCount = 0,
            failure = null,
            verification = null,
            remotePaused = false,
            snapshotVersion = 1L,
            queueOrder = 0L,
        )
    }

    private fun richSnapshot(): TransferSnapshot {
        val digest = requireNotNull(Sha256Digest.fromHex("0123456789abcdef".repeat(4)))
        val base = queuedSnapshot()
        val descriptor = base.descriptor.copy(
            fileId = FileId("file-rich"),
            displayName = "résumé.tar",
            relativePath = RelativeTransferPath("archive/résumé.tar"),
            mimeType = "application/x-tar",
            lastModifiedEpochMillis = Long.MAX_VALUE,
            isFolderArchive = true,
            expectedSha256 = digest,
            chunkSize = ChunkSize(8_192),
        )
        return base.copy(
            transferId = TransferId("transfer-rich"),
            sessionId = SessionId("session-rich"),
            batchId = BatchId("batch-rich"),
            recipientId = RecipientId("recipient-rich"),
            direction = SessionDirection.INBOUND,
            descriptor = descriptor,
            retryCount = 1,
            failure = TransferError.UnexpectedInternal("/data/user/0/private"),
            verification = VerificationInfo(digest, null, 1L),
            remotePaused = true,
            snapshotVersion = 4L,
            queueOrder = 7L,
        )
    }

    private fun openDatabase(name: String = databaseName()): MorseDatabase =
        Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)

    private fun databaseName(): String = "transfer-${UUID.randomUUID()}.db".also(databaseNames::add)
}
