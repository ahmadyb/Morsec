package app.morsecode.core.storage.transfer

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.db.MORSE_MIGRATION_1_2
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSafCommitRestorationReconstructionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<MorseDatabase>()
    private val names = mutableListOf<String>()
    private val treeUri = Uri.parse("content://provider/tree/root")
    private val grant = SafTreeGrant("9", treeUri, "root", "provider", writable = true)
    private val bytes = "reconstructed database recovery".toByteArray()

    @After
    fun closeDatabases() {
        databases.forEach(MorseDatabase::close)
        names.forEach(context::deleteDatabase)
    }

    @Test
    fun `explicit restoration after database reconstruction resumes persisted checkpoint`() = runBlocking {
        val name = databaseName()
        val firstDatabase = openDatabase(name)
        val checkpoint = checkpoint()
        assertEquals(
            SafCommitJournalWrite.Saved(1L),
            RoomSafCommitJournal(firstDatabase, Dispatchers.IO).write(checkpoint, expectedRevision = 0L),
        )
        firstDatabase.close()
        databases.remove(firstDatabase)

        val reconstructedDatabase = openDatabase(name)
        val journal = RoomSafCommitJournal(reconstructedDatabase, Dispatchers.IO)
        assertEquals(
            SafCommitJournalRead.Found(SafCommitJournalEntry(checkpoint, 1L)),
            journal.read(checkpoint.commitId),
        )
        val gateway = RecordingSafGateway()
        val staging = MemoryStaging(bytes)
        val factory = RecoveryFactory(gateway, staging, journal)
        val restoration = SafCommitRestorationCoordinator(
            discovery = journal,
            grantResolver = GrantResolver(),
            recoveryFactory = factory,
            policy = SafCommitRestorationPolicy(
                maxCheckpointsPerRun = 2,
                pageSize = 2,
                maxProviderMutationsPerRun = 16,
                maxCleanupAttemptsPerRun = 8,
                maxReconciliationObservationsPerCheckpoint = 64,
                maxReturnedRetryPlans = 2,
            ),
            dispatcher = Dispatchers.IO,
        )

        // Opening/reconstructing the database and constructing the callable API are inert.
        assertEquals(0, factory.calls)
        assertTrue(gateway.calls.isEmpty())

        val report = restoration.restore()

        assertEquals(SafCommitRestorationDisposition.COMMITTED, report.checkpoints.single().disposition)
        assertEquals(1, gateway.countOf("create:"))
        assertTrue(staging.deleted)
        reconstructedDatabase.close()
        databases.remove(reconstructedDatabase)

        val reopenedAfterRecovery = openDatabase(name)
        val stored = RoomSafCommitJournal(reopenedAfterRecovery, Dispatchers.IO).read(checkpoint.commitId)
        assertTrue(stored is SafCommitJournalRead.Found)
        val recovered = (stored as SafCommitJournalRead.Found).entry.checkpoint
        assertEquals(SafCommitCheckpointPhase.COMMITTED, recovered.phase)
        assertTrue(recovered.stagingReleased)
    }

    private fun checkpoint(): SafCommitCheckpoint {
        val record = SafCommitRecord(
            sessionId = SessionId("session-reconstruction"),
            transferId = TransferId("transfer-reconstruction"),
            partialId = PartialIdentity("room-reconstruction"),
            treeUri = treeUri.toString(),
            rootDocumentId = "root",
            parentDocumentId = "root",
            expectedFinalName = "reconstructed.bin",
            expectedSizeBytes = bytes.size.toLong(),
            expectedDigest = Sha256Accumulator().apply { update(bytes) }.digest(),
            grantId = grant.grantId,
            duplicatePolicy = DuplicatePolicy.RENAME,
        )
        return SafCommitCheckpoint.fromRecord(record, grant)
    }

    private class GrantResolver : SafCommitGrantResolver {
        override suspend fun resolve(checkpoint: SafCommitCheckpoint): SafCommitGrantResolution =
            SafCommitGrantResolution.Available(SafTreeGrant(
                checkpoint.approvedTree.grantId,
                Uri.parse(checkpoint.approvedTree.treeUri),
                checkpoint.approvedTree.rootDocumentId,
                checkpoint.approvedTree.authority,
                writable = true,
            ))
    }

    private class RecoveryFactory(
        private val gateway: RecordingSafGateway,
        private val staging: SafStaging,
        private val journal: SafCommitJournal,
    ) : SafCommitRecoveryCoordinatorFactory {
        var calls = 0

        override fun create(
            budget: SafCommitExecutionBudget,
            isCancelled: () -> Boolean,
        ): SafCommitCoordinator {
            calls++
            return SafCommitCoordinator(
                gateway = BudgetedSafDocumentGateway(gateway, budget),
                staging = BudgetedSafStaging(staging, budget),
                journal = journal,
                isCancelled = isCancelled,
            )
        }
    }

    private class MemoryStaging(private val content: ByteArray) : SafStaging {
        var deleted = false
            private set
        override fun length(identity: PartialIdentity): Long? = if (deleted) null else content.size.toLong()
        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(content)))
        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }

    private fun openDatabase(name: String): MorseDatabase =
        Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)

    private fun databaseName(): String = "saf-restoration-${UUID.randomUUID()}.db".also(names::add)
}
