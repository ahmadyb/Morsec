package app.morsecode.core.data.db

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo
import app.morsecode.core.transfer.persistence.StoreReadResult
import app.morsecode.core.transfer.persistence.StoreResult
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.reducer.TransitionResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A fresh real Room v2 database and real SQLite files on API 23, never Robolectric. */
@RunWith(AndroidJUnit4::class)
class RoomStorageApi23Test {
    private lateinit var context: Context
    private lateinit var name: String
    private val databases = mutableListOf<MorseDatabase>()
    private val digest = requireNotNull(Sha256Digest.fromHex("a".repeat(64)))

    @Before
    fun setUp() {
        assertEquals(23, Build.VERSION.SDK_INT)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "slice4s-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        databases.clear()
        context.deleteDatabase(name)
        assertFalse(context.getDatabasePath(name).exists())
    }

    // No migrations or destructive fallback are registered: this test creates
    // a new v2 database, closes it, and reopens that same v2 database.
    private fun open(): MorseDatabase = Room.databaseBuilder(context, MorseDatabase::class.java, name)
        .build().also(databases::add)

    private fun close(database: MorseDatabase) {
        database.close()
        databases.remove(database)
    }

    private fun store(database: MorseDatabase): RoomTransferSnapshotStore =
        RoomTransferSnapshotStore(database, Dispatchers.IO)

    private fun assertV2(database: MorseDatabase) {
        database.openHelper.readableDatabase.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
        database.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            assertTrue(it.moveToFirst())
            assertEquals("acb834a1278778076f87fd6d213c1405", it.getString(0))
        }
    }

    private fun receiving(): TransferSnapshot {
        val descriptor = TransferFileDescriptor(
            fileId = FileId("slice4s-file"), displayName = "fixture.bin",
            relativePath = RelativeTransferPath("fixture.bin"), mimeType = "application/octet-stream",
            totalBytes = 8_192L, lastModifiedEpochMillis = null, isFolderArchive = false,
            expectedSha256 = digest, chunkSize = ChunkSize(4_096), protocolVersion = ProtocolVersion.CURRENT,
        )
        return TransferSnapshot(
            transferId = TransferId("slice4s-transfer"), sessionId = SessionId("slice4s-session"),
            batchId = BatchId("slice4s-batch"), recipientId = null, direction = SessionDirection.INBOUND,
            descriptor = descriptor, state = TransferState.RECEIVING, confirmedBytes = 0L, optimisticBytes = 0L,
            lastAcknowledgedSequence = null, retryCount = 0, failure = null, verification = null,
            remotePaused = false, snapshotVersion = 1L, queueOrder = 0L,
        )
    }

    private fun staged(before: TransferSnapshot): TransitionResult.Accepted = TransferReducer.apply(
        before,
        TransferEvent.ChunkDurablyStored(before.sessionId, before.transferId, before.recipientId, 0L, 4_096, 0L),
    ) as TransitionResult.Accepted

    private fun verified(store: RoomTransferSnapshotStore): TransferSnapshot {
        val original = receiving().let {
            it.copy(
                state = TransferState.VERIFYING, confirmedBytes = it.totalBytes, optimisticBytes = it.totalBytes,
                verification = VerificationInfo(digest, null, it.snapshotVersion),
            )
        }
        assertEquals(StoreResult.Ok, store.saveTransition(null, original, emptyList()))
        val result = TransferReducer.apply(
            original,
            TransferEvent.VerificationSucceeded(original.sessionId, original.transferId, original.recipientId, digest),
        ) as TransitionResult.Accepted
        assertTrue(result.effects.single() is TransferEffect.CommitVerifiedDestination)
        assertEquals(StoreResult.Ok, store.saveTransition(original, result.current, emptyList()))
        return result.current
    }

    @Test(timeout = 20000)
    fun freshDatabaseUsesTheCommittedV2IdentityAndReopensWithoutMigration() {
        val first = open()
        assertV2(first)
        assertTrue(context.getDatabasePath(name).exists())
        close(first)
        val reopened = open()
        assertV2(reopened)
    }

    @Test(timeout = 20000)
    fun confirmedCheckpointSurvivesCloseAndReopenBeforeAnyAck() {
        val first = open()
        val store = store(first)
        val before = receiving()
        val pending = staged(before)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        assertEquals(StoreResult.Ok, store.saveTransition(before, pending.current, pending.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        close(first)
        val reopened = open()
        assertV2(reopened)
        val restored = (store(reopened).loadTransfer(before.transferId) as StoreReadResult.Found).value
        assertEquals(4_096L, restored.confirmedBytes)
        assertEquals(4_096L, restored.optimisticBytes)
        assertEquals(pending.current.snapshotVersion, restored.snapshotVersion)
        assertNull(restored.lastAcknowledgedSequence)
    }

    @Test(timeout = 20000)
    fun staleCheckpointPredecessorConflictsAndDurablePredecessorSucceeds() {
        val first = open()
        val store = store(first)
        val before = receiving()
        val pending = staged(before)
        assertEquals(StoreResult.Ok, store.saveTransition(null, before, emptyList()))
        assertEquals(StoreResult.Ok, store.saveTransition(before, pending.current, pending.effects.take(1)))
        assertEquals(StoreResult.Ok, store.saveCheckpoint(before.transferId, ConfirmedOffset(4_096L)))
        close(first)
        val reopened = open()
        val restoredStore = store(reopened)
        val durable = (restoredStore.loadTransfer(before.transferId) as StoreReadResult.Found).value
        val confirmed = TransferReducer.apply(
            pending.current,
            TransferEvent.ConfirmedOffsetPersisted(before.sessionId, before.transferId, before.recipientId, 4_096L),
        ) as TransitionResult.Accepted
        val conflict = restoredStore.saveTransition(pending.current, confirmed.current, confirmed.effects.take(1))
        assertTrue(conflict is StoreResult.Failed)
        assertTrue((conflict as StoreResult.Failed).error is TransferError.PersistenceConflict)
        assertEquals(StoreReadResult.Found(durable), restoredStore.loadTransfer(before.transferId))
        assertEquals(StoreResult.Ok, restoredStore.saveTransition(durable, confirmed.current, confirmed.effects.take(1)))
        close(reopened)
        assertEquals(StoreReadResult.Found(confirmed.current), store(open()).loadTransfer(before.transferId))
    }

    @Test(timeout = 20000)
    fun unresolvedVerifiedPublicationSurvivesReopenWithoutBeingTreatedAsCompleted() {
        val first = open()
        val provisional = verified(store(first))
        close(first)
        val reopened = open()
        val restored = (store(reopened).loadTransfer(provisional.transferId) as StoreReadResult.Found).value
        assertEquals(provisional, restored)
        assertEquals(TransferState.VERIFYING, restored.state)
        assertTrue(restored.verification!!.allowsCommit)
        assertTrue(TransferReducer.apply(
            restored, TransferCommand.CancelLocally(restored.sessionId, restored.transferId, restored.recipientId),
        ) is TransitionResult.Rejected)
        assertTrue(TransferReducer.apply(
            restored, TransferEvent.TransportDisconnected(restored.sessionId, restored.transferId, restored.recipientId),
        ) is TransitionResult.Rejected)
    }

    @Test(timeout = 20000)
    fun failedCompletionSaveLeavesThePublicationOutcomeUnresolvedAfterReopen() {
        val first = open()
        val snapshotStore = store(first)
        val provisional = verified(snapshotStore)
        val reported = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitSucceeded(provisional.sessionId, provisional.transferId, provisional.recipientId),
        ) as TransitionResult.Accepted
        // Inject an actual SQLite failure after an explicitly simulated report
        // of publication. The trigger is test-database-local, not a migration.
        first.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER slice4s_reject_completion BEFORE UPDATE ON transfer_snapshots " +
                "BEGIN SELECT RAISE(ABORT, 'slice4s injected failure'); END",
        )
        assertTrue(snapshotStore.saveTransition(
            provisional, reported.current, reported.effects.drop(1).take(1),
        ) is StoreResult.Failed)
        close(first)
        val reopened = open()
        assertV2(reopened)
        val restored = (store(reopened).loadTransfer(provisional.transferId) as StoreReadResult.Found).value
        assertEquals(provisional, restored)
        assertEquals(TransferState.VERIFYING, restored.state)
        assertTrue(restored.verification!!.allowsCommit)
        assertTrue(TransferReducer.apply(
            restored, TransferCommand.Retry(restored.sessionId, restored.transferId, restored.recipientId),
        ) is TransitionResult.Rejected)
        // No publisher or reconciliation implementation is connected here.
        Log.i("Morsec4S", "room_v2 failed_completion_save=reopened_provisional publication=unresolved")
    }

    @Test(timeout = 20000)
    fun completedRevisionSurvivesReopenAfterAnExplicitSimulatedCommitOutcome() {
        val first = open()
        val provisional = verified(store(first))
        close(first)
        val reopened = open()
        val restoredStore = store(reopened)
        val restored = (restoredStore.loadTransfer(provisional.transferId) as StoreReadResult.Found).value
        // This is an explicit test event, not evidence that a provider published.
        val completed = TransferReducer.apply(
            restored, TransferEvent.DestinationCommitSucceeded(restored.sessionId, restored.transferId, restored.recipientId),
        ) as TransitionResult.Accepted
        assertEquals(TransferState.COMPLETED, completed.current.state)
        assertEquals(StoreResult.Ok, restoredStore.saveTransition(restored, completed.current, completed.effects.drop(1).take(1)))
        close(reopened)
        val finalDb = open()
        assertV2(finalDb)
        assertEquals(StoreReadResult.Found(completed.current), store(finalDb).loadTransfer(provisional.transferId))
    }
}
