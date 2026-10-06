package app.morsecode.core.storage.transfer

import android.content.Context
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.db.MORSE_MIGRATION_1_2
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.TransferSnapshotEntity
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSafCommitDiscoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<MorseDatabase>()
    private val names = mutableListOf<String>()

    @After
    fun closeDatabases() {
        databases.forEach(MorseDatabase::close)
        names.forEach(context::deleteDatabase)
    }

    @Test
    fun `keyset pages are ordered bounded and omit only completed cleanup-free checkpoints`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val ready = checkpoint("a-ready")
        val terminal = checkpoint("b-terminal").copy(
            phase = SafCommitCheckpointPhase.COMMITTED,
            finalIdentity = documentIdentity("final-terminal"),
            copiedBytes = checkpoint("b-terminal").expectedSizeBytes,
            stagingReleased = true,
        )
        val reconciling = checkpoint("c-reconcile").copy(
            phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
        )
        val cleanup = checkpoint("d-cleanup").copy(
            phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            pendingCleanup = listOf(
                SafPendingCleanupIdentity(
                    type = SafCleanupPending.STAGING,
                    stagingIdentity = PartialIdentity("d-cleanup"),
                ),
            ),
        )
        listOf(ready, terminal, reconciling, cleanup).forEach { value ->
            assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(value, 0L))
        }

        val first = journal.restorationPage(after = null, limit = 2) as SafCommitDiscoveryPageResult.Page
        assertEquals(listOf("a-ready", "c-reconcile"), first.value.candidates.map { it.commitId?.value })
        assertTrue(first.value.hasMore)
        assertEquals("c-reconcile", first.value.nextCursor?.afterCommitId)
        assertTrue(first.value.candidates.all { it.error == null && it.checkpoint != null })

        val second = journal.restorationPage(first.value.nextCursor, limit = 2) as SafCommitDiscoveryPageResult.Page
        assertEquals(listOf("d-cleanup"), second.value.candidates.map { it.commitId?.value })
        assertFalse(second.value.hasMore)
        assertEquals("d-cleanup", second.value.nextCursor?.afterCommitId)
    }

    @Test
    fun `transfer activity is provider free and active or malformed rows are classified conservatively`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val active = checkpoint("active-transfer")
        val malformed = checkpoint("malformed-activity")
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(active, 0L))
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(malformed, 0L))
        runBlocking {
            database.transferSnapshotDao().upsert(snapshotRow(active, "receiving"))
            database.transferSnapshotDao().upsert(snapshotRow(malformed, "unknown_state"))
        }

        val page = journal.restorationPage(after = null, limit = 8) as SafCommitDiscoveryPageResult.Page
        val byKey = page.value.candidates.associateBy { it.commitId?.value }
        assertEquals(SafCommitTransferActivity.ACTIVE_OR_RESUMABLE, byKey.getValue("active-transfer").transferActivity)
        assertEquals(SafCommitTransferActivity.MALFORMED, byKey.getValue("malformed-activity").transferActivity)
        assertEquals(
            SafCommitTransferActivity.ACTIVE_OR_RESUMABLE,
            (journal.readForRestoration(active.commitId) as SafCommitDiscoveryReadResult.Found)
                .candidate.transferActivity,
        )
    }

    private fun checkpoint(commitKey: String): SafCommitCheckpoint {
        val treeUri = DocumentsContract.buildTreeDocumentUri("example.provider", "root")
        val grant = SafTreeGrant(
            grantId = "1",
            treeUri = treeUri,
            rootDocumentId = "root",
            authority = "example.provider",
            writable = true,
        )
        val record = SafCommitRecord(
            sessionId = SessionId("session-$commitKey"),
            transferId = TransferId("transfer-$commitKey"),
            partialId = PartialIdentity(commitKey),
            treeUri = treeUri.toString(),
            rootDocumentId = "root",
            parentDocumentId = "root",
            expectedFinalName = "file.bin",
            expectedSizeBytes = 8L,
            grantId = grant.grantId,
            duplicatePolicy = DuplicatePolicy.RENAME,
        )
        return SafCommitCheckpoint.fromRecord(record, grant)
    }

    private fun documentIdentity(id: String): SafStoredDocumentIdentity {
        val treeUri = DocumentsContract.buildTreeDocumentUri("example.provider", "root")
        return SafStoredDocumentIdentity(
            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
            documentId = id,
        )
    }

    private fun snapshotRow(checkpoint: SafCommitCheckpoint, state: String) = TransferSnapshotEntity(
        transferId = checkpoint.transferId.value,
        sessionId = checkpoint.sessionId.value,
        batchId = "batch",
        recipientId = null,
        direction = "RECEIVE",
        snapshotState = state,
        snapshotVersion = 1L,
        confirmedBytes = 0L,
        optimisticBytes = 0L,
        lastAcknowledgedSequence = null,
        retryCount = 0,
        failureCode = null,
        failureDetail = null,
        failureRetryable = null,
        failureOrigin = null,
        failureCategory = null,
        remotePaused = 0,
        queueOrder = 0L,
        fileId = "file-${checkpoint.transferId.value}",
        displayName = "file.bin",
        relativePath = "",
        mimeType = "application/octet-stream",
        totalBytes = checkpoint.expectedSizeBytes,
        lastModifiedEpochMillis = null,
        isFolderArchive = 0,
        expectedSha256Hex = null,
        chunkSize = 65_536,
        protocolVersion = 1,
        verificationExpectedDigestHex = null,
        verificationObservedDigestHex = null,
        verificationStartedSnapshotVersion = null,
        rowRevision = 1L,
    )

    private fun openDatabase(): MorseDatabase =
        Room.databaseBuilder(context, MorseDatabase::class.java, databaseName())
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)

    private fun databaseName(): String = "saf-discovery-${UUID.randomUUID()}.db".also(names::add)
}
