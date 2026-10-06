package app.morsecode.core.storage.transfer

import android.content.Context
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.db.MORSE_MIGRATION_1_2
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.SafPendingCleanupEntity
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSafCommitJournalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<MorseDatabase>()
    private val names = mutableListOf<String>()

    @After
    fun closeDatabases() {
        databases.forEach(MorseDatabase::close)
        names.forEach { name -> context.deleteDatabase(name) }
    }

    @Test
    fun `journal uses typed CAS revisions and survives reopen`() {
        val name = databaseName()
        val firstDb = openDatabase(name)
        val journal = RoomSafCommitJournal(firstDb, Dispatchers.IO)
        val checkpoint = checkpoint()
        assertEquals(SafCommitJournalRead.Missing, journal.read(checkpoint.commitId))
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(checkpoint, expectedRevision = 0L))

        val next = checkpoint.copy(phase = SafCommitCheckpointPhase.COPY_STARTED, copiedBytes = 1_024L)
        assertTrue(journal.write(next, expectedRevision = 0L) is SafCommitJournalWrite.Conflict)
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(next, expectedRevision = 1L))
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(next, expectedRevision = 1L))
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(next, 2L)), journal.read(next.commitId))

        firstDb.close()
        databases.remove(firstDb)
        val reopened = RoomSafCommitJournal(openDatabase(name), Dispatchers.IO)
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(next, 2L)), reopened.read(next.commitId))
    }

    @Test
    fun `every persisted phase strategy and duplicate policy round trips`() {
        val journal = RoomSafCommitJournal(openDatabase(), Dispatchers.IO)
        SafCommitCheckpointPhase.entries.forEachIndexed { index, phase ->
            val base = checkpoint(commitKey = "phase-$index")
            val value = if (phase == SafCommitCheckpointPhase.COMMITTED) {
                base.copy(
                    phase = phase,
                    copiedBytes = base.expectedSizeBytes,
                    finalIdentity = storedIdentity("final-$index"),
                    stagingReleased = true,
                )
            } else {
                base.copy(phase = phase)
            }
            assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(value, expectedRevision = 0L))
            assertEquals(
                SafCommitJournalRead.Found(SafCommitJournalEntry(value, 1L)),
                journal.read(value.commitId),
            )
        }

        var index = 0
        for (strategy in SafCommitStrategy.entries) {
            for (policy in DuplicatePolicy.entries) {
                val value = checkpoint(
                    commitKey = "policy-${index++}",
                    strategy = strategy,
                    duplicatePolicy = policy,
                    expectedFinalName = "résumé-📦.bin",
                    expectedSizeBytes = app.morsecode.core.transfer.ProtocolLimits.MAX_FILE_SIZE_BYTES,
                )
                assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(value, expectedRevision = 0L))
                assertEquals(
                    SafCommitJournalRead.Found(SafCommitJournalEntry(value, 1L)),
                    journal.read(value.commitId),
                )
            }
        }
    }

    @Test
    fun `all optional identities typed failure digest and cleanup types round trip`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint(
            expectedFinalName = "résumé-📦.bin",
            expectedSizeBytes = app.morsecode.core.transfer.ProtocolLimits.MAX_FILE_SIZE_BYTES,
        )
        val existing = storedIdentity("existing-large")
        val backup = storedIdentity("backup-large")
        val temporary = storedIdentity("temporary-large")
        val returned = storedIdentity("returned-large")
        val digest = app.morsecode.core.transfer.integrity.Sha256Digest.fromHex("ab".repeat(32))!!
        val scope = SafRenameScope(
            grantId = base.approvedTree.grantId,
            treeUri = base.approvedTree.treeUri,
            authority = base.approvedTree.authority,
            rootDocumentId = base.approvedTree.rootDocumentId,
            parentDocumentId = base.parentDocumentId,
            sessionId = base.sessionId,
            transferId = base.transferId,
            commitId = base.commitId,
        )
        val history = listOf(
            SafRenameEvidence(
                before = existing,
                returned = backup,
                reconciliation = SafRenameReconciliation.RESOLVED_TO_RETURNED,
                scope = scope,
                phase = SafRenamePhase.BACKUP_RENAME,
                sequence = 0,
            ),
            SafRenameEvidence(
                before = temporary,
                returned = returned,
                reconciliation = SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE,
                scope = scope,
                phase = SafRenamePhase.FINAL_PROMOTION,
                sequence = 1,
            ),
        )
        val value = base.copy(
            phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            strategy = SafCommitStrategy.TEMP_THEN_RENAME,
            duplicatePolicy = DuplicatePolicy.OVERWRITE,
            expectedFinalName = "résumé-📦.bin",
            expectedSizeBytes = app.morsecode.core.transfer.ProtocolLimits.MAX_FILE_SIZE_BYTES,
            expectedDigest = digest,
            temporaryIdentity = temporary,
            existingIdentity = existing,
            backupIdentity = backup,
            returnedRenameUri = returned.documentUri,
            returnedRenameIdentity = returned,
            renameHistory = history,
            pendingCleanup = listOf(
                SafPendingCleanupIdentity(SafCleanupPending.STAGING, stagingIdentity = base.stagingIdentity),
                SafPendingCleanupIdentity(SafCleanupPending.PROVIDER_TEMPORARY, documentIdentity = temporary),
                SafPendingCleanupIdentity(SafCleanupPending.BACKUP, documentIdentity = backup),
            ),
            copiedBytes = app.morsecode.core.transfer.ProtocolLimits.MAX_FILE_SIZE_BYTES,
            stagingReleased = false,
            lastFailure = SafCommitCheckpointFailure("io", "io"),
            verifiedDigest = digest,
        )

        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(value, expectedRevision = 0L))
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(value, 1L)), journal.read(value.commitId))
        assertEquals(3, runBlocking { database.safPendingCleanupDao().countForCommit(value.commitId.value) })
    }

    @Test
    fun `rename evidence and exact cleanup children replace atomically`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(base, 0L))

        val temporary = storedIdentity("temp-identity")
        val returned = storedIdentity("returned-identity")
        val scope = SafRenameScope(
            grantId = base.approvedTree.grantId,
            treeUri = base.approvedTree.treeUri,
            authority = base.approvedTree.authority,
            rootDocumentId = base.approvedTree.rootDocumentId,
            parentDocumentId = base.parentDocumentId,
            sessionId = base.sessionId,
            transferId = base.transferId,
            commitId = base.commitId,
        )
        val evidence = SafRenameEvidence(
            before = temporary,
            returned = returned,
            reconciliation = SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE,
            scope = scope,
            phase = SafRenamePhase.FINAL_PROMOTION,
            sequence = 0,
        )
        val replacement = base.copy(
            phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            temporaryIdentity = temporary,
            returnedRenameUri = returned.documentUri,
            returnedRenameIdentity = returned,
            renameHistory = listOf(evidence),
            pendingCleanup = listOf(
                SafPendingCleanupIdentity(
                    type = SafCleanupPending.STAGING,
                    stagingIdentity = base.stagingIdentity,
                ),
            ),
        )
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(replacement, 1L))
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(replacement, 1L))
        assertEquals(1, runBlocking { database.safRenameHistoryDao().forCommit(base.commitId.value).size })
        assertEquals(1, runBlocking { database.safPendingCleanupDao().countForCommit(base.commitId.value) })

        val read = journal.read(base.commitId)
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(replacement, 2L)), read)
        assertTrue((read as SafCommitJournalRead.Found).entry.checkpoint.renameHistory.single().reconciliation ==
            SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE)
        assertEquals(base.stagingIdentity, read.entry.checkpoint.pendingCleanup.single().stagingIdentity)
    }

    @Test
    fun `child insertion failure rolls back parent revision and all replacement children`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(base, 0L))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_pending_cleanup_insert " +
                "BEFORE INSERT ON saf_pending_cleanup WHEN NEW.commit_id = '${base.commitId.value}' " +
                "BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END",
        )
        val replacement = checkpointWithOwnedChildren(base)

        val failed = journal.write(replacement, expectedRevision = 1L)

        assertTrue(failed is SafCommitJournalWrite.Conflict || failed is SafCommitJournalWrite.Rejected)
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(base, 1L)), journal.read(base.commitId))
        assertEquals(0, runBlocking { database.safRenameHistoryDao().forCommit(base.commitId.value).size })
        assertEquals(0, runBlocking { database.safPendingCleanupDao().countForCommit(base.commitId.value) })
    }

    @Test
    fun `parallel compare and set writers cannot overwrite each other`() = runBlocking {
        val database = openDatabase()
        val firstJournal = RoomSafCommitJournal(database, Dispatchers.IO)
        val secondJournal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), firstJournal.write(base, expectedRevision = 0L))
        val first = base.copy(phase = SafCommitCheckpointPhase.COPY_STARTED, copiedBytes = 1_024L)
        val second = base.copy(phase = SafCommitCheckpointPhase.COPY_STARTED, copiedBytes = 2_048L)

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { firstJournal.write(first, expectedRevision = 1L) },
                async(Dispatchers.Default) { secondJournal.write(second, expectedRevision = 1L) },
            ).map { it.await() }
        }

        assertEquals(1, results.count { it is SafCommitJournalWrite.Saved })
        assertEquals(1, results.count { it is SafCommitJournalWrite.Conflict })
        val current = firstJournal.read(base.commitId) as SafCommitJournalRead.Found
        assertEquals(2L, current.entry.revision)
        assertTrue(current.entry.checkpoint == first || current.entry.checkpoint == second)
    }

    @Test
    fun `deleting one parent cascades only its owned child rows`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val first = checkpoint(commitKey = "parent-first")
        val second = checkpoint(commitKey = "parent-second")
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(first, expectedRevision = 0L))
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(second, expectedRevision = 0L))
        val firstChildren = checkpointWithOwnedChildren(first)
        val secondChildren = checkpointWithOwnedChildren(second)
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(firstChildren, expectedRevision = 1L))
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(secondChildren, expectedRevision = 1L))

        runBlocking { database.transferPartialDao().delete(first.commitId.value) }

        assertEquals(0, runBlocking { database.safPendingCleanupDao().countForCommit(first.commitId.value) })
        assertEquals(2, runBlocking { database.safPendingCleanupDao().countForCommit(second.commitId.value) })
        assertEquals(SafCommitJournalRead.Missing, journal.read(first.commitId))
        assertEquals(SafCommitJournalRead.Found(SafCommitJournalEntry(secondChildren, 2L)), journal.read(second.commitId))
    }

    @Test
    fun `unknown tokens malformed digest identity and child ownership are typed rejections`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(base, 0L))
        val parent = runBlocking { database.transferPartialDao().find(base.commitId.value)!! }
        val malformedParents = listOf(
            parent.copy(strategyId = "future_strategy"),
            parent.copy(duplicatePolicy = "future_policy"),
            parent.copy(checkpointPhase = "future_phase"),
            parent.copy(expectedDigestHex = "aabb"),
            parent.copy(expectedDigestHex = "AB".repeat(32)),
            parent.copy(lastFailureCategory = "future_category", lastFailureCode = "io"),
            parent.copy(temporaryUri = "file:///private/document", temporaryDocumentId = "document"),
        )
        for (malformed in malformedParents) {
            runBlocking { database.transferPartialDao().upsert(malformed) }
            val result = journal.read(base.commitId)
            assertTrue(result is SafCommitJournalRead.Rejected)
            assertEquals(
                TransferStorageError.StateConflict("checkpoint_malformed"),
                (result as SafCommitJournalRead.Rejected).error,
            )
        }

        // The last injected row was malformed; restore the validated preimage before
        // exercising a complete replacement. A production write must not repair or
        // overwrite corrupted state implicitly.
        runBlocking { database.transferPartialDao().upsert(parent) }
        val withCleanup = checkpointWithOwnedChildren(base)
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(withCleanup, 1L))
        val cleanup = runBlocking {
            database.safPendingCleanupDao().forCommit(base.commitId.value).first()
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE saf_pending_cleanup SET staging_identity = ? WHERE commit_id = ? AND sequence = ?",
            arrayOf("different-staging", base.commitId.value, cleanup.sequence),
        )
        val invalidOwnership = journal.read(base.commitId)
        assertTrue(invalidOwnership is SafCommitJournalRead.Rejected)
        assertEquals(
            TransferStorageError.StateConflict("checkpoint_malformed"),
            (invalidOwnership as SafCommitJournalRead.Rejected).error,
        )
    }

    @Test
    fun `missing required child and noncontiguous child order reject the snapshot`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(base, 0L))
        val replacement = checkpointWithOwnedChildren(base)
        assertEquals(SafCommitJournalWrite.Saved(2L), journal.write(replacement, 1L))
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM saf_pending_cleanup WHERE commit_id = ? AND sequence = ?",
            arrayOf(base.commitId.value, 0),
        )
        val missing = journal.read(base.commitId)
        assertTrue(missing is SafCommitJournalRead.Rejected)

        // Restore one cleanup row and corrupt its stable sequence without
        // changing the parent's expected count.
        runBlocking {
            database.safPendingCleanupDao().insertAll(
                listOf(
                    SafPendingCleanupEntity(
                        commitId = base.commitId.value,
                        sequence = 2,
                        cleanupType = SafCleanupPending.STAGING.id,
                        documentUri = null,
                        documentId = null,
                        stagingIdentity = base.stagingIdentity.value,
                    ),
                ),
            )
        }
        val unordered = journal.read(base.commitId)
        assertTrue(unordered is SafCommitJournalRead.Rejected)
    }

    @Test
    fun `dispatcher failure returns a typed persistence failure`() {
        val failingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw IllegalStateException("injected dispatcher failure")
            }
        }
        val journal = RoomSafCommitJournal(openDatabase(), failingDispatcher)

        val result = journal.read(PartialIdentity("closed-journal"))

        assertTrue(result is SafCommitJournalRead.Rejected)
        assertEquals(
            TransferStorageError.Io("journal_read"),
            (result as SafCommitJournalRead.Rejected).error,
        )
    }

    @Test
    fun `journal cursor preserves cancellation as a typed persistence result`() {
        val cancellingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw CancellationException("injected cancellation")
            }
        }
        val cursor = SafCommitJournalCursor(RoomSafCommitJournal(openDatabase(), cancellingDispatcher))
        val checkpoint = checkpoint()

        val readFailure = try {
            cursor.load(checkpoint.commitId)
            null
        } catch (failure: SafCommitJournalReadException) {
            failure
        }
        assertEquals(TransferStorageError.Cancelled, readFailure?.error)
        assertEquals(false, cursor.save(checkpoint))
        assertEquals(TransferStorageError.Cancelled, cursor.consumeWriteFailure())
    }

    @Test
    fun `malformed commit lookup keys are rejected before Room access`() {
        val failingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw IllegalStateException("Room must not be reached for an invalid key")
            }
        }
        val journal = RoomSafCommitJournal(openDatabase(), failingDispatcher)

        assertEquals(
            SafCommitJournalRead.Rejected(TransferStorageError.StateConflict("checkpoint_malformed")),
            journal.read(PartialIdentity("invalid commit id")),
        )
    }

    @Test
    fun `unknown persisted checkpoint version is rejected as unsupported`() {
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val base = checkpoint()
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(base, 0L))
        runBlocking {
            val row = database.transferPartialDao().find(base.commitId.value)!!
            database.transferPartialDao().upsert(row.copy(checkpointVersion = SafCommitCheckpoint.CURRENT_VERSION + 1))
        }

        val result = journal.read(base.commitId)

        assertTrue(result is SafCommitJournalRead.Rejected)
        assertEquals(
            TransferStorageError.Unsupported("saf_checkpoint_version"),
            (result as SafCommitJournalRead.Rejected).error,
        )
    }

    private fun checkpoint(
        commitKey: String = "partial-journal",
        strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,
        duplicatePolicy: DuplicatePolicy = DuplicatePolicy.OVERWRITE,
        expectedFinalName: String = "journal.bin",
        expectedSizeBytes: Long = 4_096L,
    ): SafCommitCheckpoint {
        val treeUri = DocumentsContract.buildTreeDocumentUri("example.provider", "root")
        val uri = treeUri.toString()
        val grant = SafTreeGrant(
            grantId = "grant-journal",
            treeUri = treeUri,
            rootDocumentId = "root",
            authority = "example.provider",
            writable = true,
        )
        val record = SafCommitRecord(
            sessionId = SessionId("session-journal"),
            transferId = TransferId("transfer-journal"),
            partialId = PartialIdentity(commitKey),
            treeUri = uri,
            rootDocumentId = "root",
            parentDocumentId = "root",
            expectedFinalName = expectedFinalName,
            expectedSizeBytes = expectedSizeBytes,
            grantId = grant.grantId,
            duplicatePolicy = duplicatePolicy,
        )
        return SafCommitCheckpoint.fromRecord(record, grant).copy(strategy = strategy)
    }

    private fun checkpointWithOwnedChildren(base: SafCommitCheckpoint): SafCommitCheckpoint {
        val temporary = storedIdentity("temp-rollback")
        val backup = storedIdentity("backup-rollback")
        return base.copy(
            phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            temporaryIdentity = temporary,
            backupIdentity = backup,
            pendingCleanup = listOf(
                SafPendingCleanupIdentity(
                    type = SafCleanupPending.STAGING,
                    stagingIdentity = base.stagingIdentity,
                ),
                SafPendingCleanupIdentity(
                    type = SafCleanupPending.BACKUP,
                    documentIdentity = backup,
                ),
            ),
        )
    }

    private fun storedIdentity(id: String): SafStoredDocumentIdentity {
        val treeUri = DocumentsContract.buildTreeDocumentUri("example.provider", "root")
        return SafStoredDocumentIdentity(
            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
            documentId = id,
        )
    }

    private fun openDatabase(name: String = databaseName()): MorseDatabase =
        Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)

    private fun databaseName(): String = "saf-journal-${UUID.randomUUID()}.db".also(names::add)
}
