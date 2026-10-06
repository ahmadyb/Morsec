package app.morsecode.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.error.TransferError
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
    fun `typed descriptor digest failure and verification columns survive reopen`() {
        val name = databaseName()
        val firstDb = openDatabase(name)
        val snapshot = richSnapshot()
        val store = RoomTransferSnapshotStore(firstDb, Dispatchers.IO)
        assertEquals(StoreResult.Ok, store.saveTransition(null, snapshot, emptyList()))

        val stored = firstDb.transferSnapshotDao().find(snapshot.transferId.value)!!
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
