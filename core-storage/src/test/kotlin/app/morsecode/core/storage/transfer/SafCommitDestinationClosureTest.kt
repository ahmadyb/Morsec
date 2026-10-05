package app.morsecode.core.storage.transfer

import android.net.Uri
import android.provider.DocumentsContract
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitDestinationClosureTest {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val parentId = "primary:Download"
    private val grant = SafTreeGrant(
        grantId = "grant-closure",
        treeUri = Uri.parse(treeUri),
        rootDocumentId = parentId,
        authority = requireNotNull(Uri.parse(treeUri).authority),
        writable = true,
    )
    private val partialId = PartialIdentity("saf-closure-1")
    private val payload = "closure-payload".toByteArray()

    private fun record(
        policy: DuplicatePolicy = DuplicatePolicy.RENAME,
        strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,
    ) = SafCommitRecord(
        sessionId = SessionId("session-closure"),
        transferId = TransferId("transfer-closure"),
        partialId = partialId,
        treeUri = treeUri,
        rootDocumentId = parentId,
        parentDocumentId = parentId,
        expectedFinalName = "movie.mp4",
        expectedSizeBytes = payload.size.toLong(),
        grantId = grant.grantId,
        strategy = strategy,
        duplicatePolicy = policy,
    )

    private class TrackingStaging(private val bytes: ByteArray) : SafStaging {
        var deleteCalls = 0
            private set
        var deleted = false
            private set
        val readHandles = mutableListOf<SafReadHandle>()

        override fun length(identity: PartialIdentity): Long? = if (deleted) null else bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen {
            val handle = SafReadHandle(ByteArrayInputStream(bytes))
            readHandles += handle
            return SafOpen.Opened(handle)
        }

        override fun delete(identity: PartialIdentity): Boolean {
            deleteCalls++
            deleted = true
            return true
        }
    }

    private inner class VirtualStaging(private val sizeBytes: Long) : SafStaging {
        var deleted = false
            private set

        override fun length(identity: PartialIdentity): Long? = if (deleted) null else sizeBytes

        override fun open(identity: PartialIdentity): SafOpen =
            if (deleted) SafOpen.Refused(TransferStorageError.NotFound("staged_copy"))
            else SafOpen.Opened(SafReadHandle(virtualInput(sizeBytes)))

        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }

    private data class VirtualRow(
        val uri: String,
        val id: String,
        var name: String,
        var sizeBytes: Long = 0L,
    )

    private inner class VirtualSafGateway(
        private val uriForId: (String) -> String,
    ) : SafDocumentGateway {
        private val rows = linkedMapOf<String, VirtualRow>()
        var createCount = 0
            private set
        var openWriteCount = 0
            private set
        var openReadCount = 0
            private set
        var deleteCount = 0
            private set

        override fun recheckPersistedGrant(
            grant: SafTreeGrant,
            operation: SafContainmentOperation,
        ): TransferStorageError? = null

        override fun findChild(parentUri: String, displayName: String): SafLookup =
            rows.values.firstOrNull { it.name == displayName }?.let { SafLookup.Found(it.info()) }
                ?: SafLookup.Absent

        override fun create(parentUri: String, mimeType: String, displayName: String): SafCreate {
            createCount++
            val id = "virtual-$createCount"
            val uri = uriForId(id)
            rows[uri] = VirtualRow(uri, id, displayName)
            return SafCreate.Created(uri, id, displayName)
        }

        override fun rename(documentUri: String, displayName: String): SafRename {
            val row = rows[documentUri] ?: return SafRename.Failed(TransferStorageError.NotFound("rename_source"))
            row.name = displayName
            return SafRename.Renamed(documentUri)
        }

        override fun delete(documentUri: String): SafDelete {
            rows.remove(documentUri)
            return SafDelete.Deleted
        }

        override fun query(documentUri: String): SafLookup =
            rows[documentUri]?.let { SafLookup.Found(it.info()) } ?: SafLookup.Absent

        override fun openWrite(documentUri: String): SafOpen {
            val row = rows[documentUri] ?: return SafOpen.Refused(TransferStorageError.NotFound("write_target"))
            openWriteCount++
            row.sizeBytes = 0L
            val sink = object : OutputStream() {
                override fun write(value: Int) {
                    row.sizeBytes = Math.addExact(row.sizeBytes, 1L)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    row.sizeBytes = Math.addExact(row.sizeBytes, length.toLong())
                }
            }
            return SafOpen.Opened(SafWriteHandle(null, sink) { })
        }

        override fun openRead(documentUri: String): SafOpen {
            val row = rows[documentUri] ?: return SafOpen.Refused(TransferStorageError.NotFound("read_source"))
            openReadCount++
            return SafOpen.Opened(SafReadHandle(virtualInput(row.sizeBytes)))
        }

        override fun deleteAndReconcile(
            documentUri: String,
            expectedDocumentId: String,
            grant: SafTreeGrant?,
        ): SafDeletion {
            deleteCount++
            val row = rows[documentUri] ?: return SafDeletion.ConfirmedAbsent()
            if (row.id != expectedDocumentId) {
                return SafDeletion.IdentityMismatch(expectedDocumentId, row.id, row.name)
            }
            rows.remove(documentUri)
            return SafDeletion.ConfirmedAbsent()
        }

        fun onlyRow(): SafDocumentInfo? = rows.values.singleOrNull()?.info()

        private fun VirtualRow.info(): SafDocumentInfo = SafDocumentInfo(
            documentUri = uri,
            documentId = id,
            displayName = name,
            sizeBytes = sizeBytes,
            mimeType = "application/octet-stream",
            flags = 0,
            isDirectory = false,
        )
    }

    private fun virtualInput(sizeBytes: Long): InputStream = object : InputStream() {
        private var remaining = sizeBytes

        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            remaining -= count.toLong()
            return count
        }
    }

    private fun virtualDigesterFactory(): () -> ChunkDigester {
        val digest = requireNotNull(Sha256Digest.fromHex("00".repeat(32)))
        return {
            object : ChunkDigester {
                override fun update(buffer: ByteArray, offset: Int, length: Int) = Unit
                override fun digest(): Sha256Digest = digest
            }
        }
    }

    private fun coordinator(
        gateway: RecordingSafGateway,
        staging: TrackingStaging,
        journal: InMemorySafCommitJournal,
        policy: DuplicatePolicy = DuplicatePolicy.RENAME,
        strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,
        allowVisibleFinalCopy: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ) = SafCommitCoordinator(
        gateway = gateway,
        staging = staging,
        journal = journal,
        allowVisibleFinalCopy = allowVisibleFinalCopy,
        isCancelled = isCancelled,
    ).let { instance ->
        CoordinatorFixture(instance, policy, strategy)
    }

    private data class CoordinatorFixture(
        val coordinator: SafCommitCoordinator,
        val policy: DuplicatePolicy,
        val strategy: SafCommitStrategy,
    ) {
        fun commit(record: SafCommitRecord, grant: SafTreeGrant): SafCommitOutcome =
            coordinator.commit(record, grant)
    }

    private fun documentUri(documentId: String): String {
        val parsed = Uri.parse(treeUri)
        val authority = requireNotNull(parsed.authority)
        val rootId = DocumentsContract.getTreeDocumentId(parsed)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, rootId)
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
    }

    private fun info(uri: String, id: String, name: String, size: Long = payload.size.toLong()) =
        SafDocumentInfo(uri, id, name, size, "application/octet-stream", 0, false)

    private fun seedOverwriteTarget(gateway: RecordingSafGateway): Pair<String, ByteArray> {
        val uri = documentUri("old-destination")
        val oldBytes = "pre-existing-user-bytes".toByteArray()
        gateway.addNamed(uri, "movie.mp4")
        gateway.written[uri] = oldBytes
        return uri to oldBytes
    }

    @Test
    fun `storage-full after a partial stream write is typed and preserves staging without rename`() {
        val gateway = RecordingSafGateway().apply {
            partialWriteFailureAfterBytes = 2L
            partialWriteFailure = storageFull()
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        val failed = outcome as SafCommitOutcome.Failed
        assertTrue(failed.error is TransferStorageError.StorageFull)
        assertEquals(2, gateway.written.values.first { it.isNotEmpty() }.size)
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
        assertEquals(SafCommitCheckpointPhase.COPY_STARTED, journal.load(partialId)?.phase)
        assertEquals("insufficient_space", journal.load(partialId)?.lastFailure?.categoryId)
        assertNotNull(failed.record.temporaryIdentity)
    }

    @Test
    fun `storage-full from writable open is checkpointed after provider creation`() {
        val gateway = RecordingSafGateway(openFailure = TransferStorageError.StorageFull("open_write"))
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        assertTrue((outcome as SafCommitOutcome.Failed).error is TransferStorageError.StorageFull)
        val checkpoint = requireNotNull(journal.load(partialId))
        assertEquals(SafCommitCheckpointPhase.COPY_STARTED, checkpoint.phase)
        assertEquals("insufficient_space", checkpoint.lastFailure?.categoryId)
        assertNotNull(checkpoint.temporaryIdentity)
        assertEquals(1, gateway.countOf("create:"))
        assertEquals(1, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, gateway.liveHandles)
        assertFalse(staging.deleted)
    }

    @Test
    fun `storage-full at stream flush or descriptor sync never advances to verification`() {
        listOf(
            RecordingSafGateway().apply {
                throwOn = { operation -> if (operation == OP_FLUSH) storageFull() else null }
            },
            RecordingSafGateway().apply {
                syncFailure = IOException("wrapped", IOException("No space left on device"))
            },
        ).forEachIndexed { index, gateway ->
            val staging = TrackingStaging(payload)
            val journal = InMemorySafCommitJournal()

            val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

            assertTrue("case $index: $outcome", outcome is SafCommitOutcome.Failed)
            assertTrue((outcome as SafCommitOutcome.Failed).error is TransferStorageError.StorageFull)
            assertEquals(SafCommitCheckpointPhase.FLUSH_INTENT, journal.load(partialId)?.phase)
            assertEquals("insufficient_space", journal.load(partialId)?.lastFailure?.categoryId)
            assertEquals(1, gateway.countOf("create:"))
            assertEquals(payload.size, gateway.written.values.first().size)
            assertEquals(0, gateway.countOf("openRead:"))
            assertEquals(0, gateway.countOf("rename:"))
            assertEquals(0, staging.deleteCalls)
            assertEquals(0, gateway.liveHandles)
        }
    }

    @Test
    fun `ordinary IO without an ENOSPC signal is not mislabeled storage-full`() {
        val gateway = RecordingSafGateway().apply {
            throwOn = { operation -> if (operation == OP_STREAM_WRITE) IOException("interrupted write") else null }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        val failed = outcome as SafCommitOutcome.Failed
        assertTrue(failed.error is TransferStorageError.Io)
        assertFalse(failed.error is TransferStorageError.StorageFull)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `storage-full during backup rename keeps the old destination untouched`() {
        val gateway = RecordingSafGateway().apply {
            failRenameAttempt = 1
            renameAttemptFailure = TransferStorageError.StorageFull("rename")
        }
        val (oldUri, oldBytes) = seedOverwriteTarget(gateway)
        val staging = TrackingStaging(payload)

        val outcome = coordinator(
            gateway,
            staging,
            InMemorySafCommitJournal(),
            policy = DuplicatePolicy.OVERWRITE,
        ).commit(record(DuplicatePolicy.OVERWRITE), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.StorageFull)
        assertTrue(oldUri in gateway.existing)
        assertTrue(gateway.written[oldUri]!!.contentEquals(oldBytes))
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `storage-full during replacement promotion leaves the verified old backup intact`() {
        val gateway = RecordingSafGateway().apply {
            failRenameAttempt = 2
            renameAttemptFailure = TransferStorageError.StorageFull("rename")
        }
        val (oldUri, oldBytes) = seedOverwriteTarget(gateway)
        val staging = TrackingStaging(payload)

        val outcome = coordinator(
            gateway,
            staging,
            InMemorySafCommitJournal(),
            policy = DuplicatePolicy.OVERWRITE,
        ).commit(record(DuplicatePolicy.OVERWRITE), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.StorageFull)
        val backup = outcome.record.backupIdentity
        assertNotNull(backup)
        assertTrue("the original survives at its exact backup identity", backup!!.documentUri in gateway.existing)
        assertTrue(gateway.written[backup.documentUri]!!.contentEquals(oldBytes))
        assertEquals(2, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertFalse("the old URI may now be the backup name", oldUri == outcome.record.finalUri)
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `grant revoked before write open remains typed and closes the staging owner`() {
        val gateway = RecordingSafGateway().apply {
            grantFailureOn = { operation ->
                if (operation == SafContainmentOperation.OPEN_WRITE) TransferStorageError.PermissionRevoked("write") else null
            }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.PermissionRevoked)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
        assertTrue(staging.readHandles.isNotEmpty())
        assertTrue(staging.readHandles.all { it.closeCount == 1 })
    }

    @Test
    fun `SecurityException during provider copy write stays PermissionRevoked`() {
        val gateway = RecordingSafGateway().apply {
            throwOn = { operation -> if (operation == OP_STREAM_WRITE) SecurityException("revoked") else null }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.PermissionRevoked)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `SecurityException during fresh provider verification read stays PermissionRevoked`() {
        val gateway = RecordingSafGateway().apply {
            throwOn = { operation -> if (operation == OP_STREAM_READ) SecurityException("revoked") else null }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.PermissionRevoked)
        assertEquals(1, gateway.countOf("openRead:"))
        assertEquals(1, gateway.countOf("closeRead:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `grant revoked before final rename stays typed and does not publish`() {
        val gateway = RecordingSafGateway().apply {
            grantFailureOn = { operation ->
                if (operation == SafContainmentOperation.RENAME) TransferStorageError.PermissionRevoked("write") else null
            }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.PermissionRevoked)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `SecurityException during rename reconciliation stays PermissionRevoked`() {
        var renameReturned = false
        val gateway = RecordingSafGateway().apply {
            afterRename = { renameReturned = true }
            throwOn = { operation ->
                if (operation == OP_QUERY && renameReturned) SecurityException("revoked during reconciliation") else null
            }
        }
        val staging = TrackingStaging(payload)

        val outcome = coordinator(gateway, staging, InMemorySafCommitJournal()).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue((outcome as SafCommitOutcome.ReconciliationRequired).error is TransferStorageError.PermissionRevoked)
        assertEquals(1, gateway.countOf("rename:"))
        assertFalse(outcome.isDelivered)
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `unexpected provider final name cannot establish authority or unlock cleanup`() {
        val gateway = RecordingSafGateway().apply {
            renameDisplayNameOverride = { requested ->
                if (requested == "movie.mp4") "movie-from-provider.mp4" else requested
            }
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal).coordinator

        val outcome = instance.commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val reconciliation = outcome as SafCommitOutcome.ReconciliationRequired
        assertEquals(TransferStorageError.ContainmentUnknown("final_child_not_found"), reconciliation.error)
        assertFalse(reconciliation.isDelivered)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, reconciliation.checkpoint?.phase)
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertEquals(0, staging.deleteCalls)
        val temporary = requireNotNull(reconciliation.checkpoint?.temporaryIdentity)
        assertEquals(null, reconciliation.checkpoint?.finalIdentity)
        assertEquals(setOf(temporary.documentUri), gateway.existing)
    }

    @Test
    fun `provider temporary cleanup producer persists an exact leftover beside the verified final`() {
        val temporaryUri = documentUri("doc-1")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
            deletionObserved = SafDeletion.StillPresent(info(temporaryUri, "doc-1", temporaryName)),
        )
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val coordinator = coordinator(gateway, staging, journal)

        val outcome = coordinator.commit(record(), grant)

        assertTrue("a fully verified final is delivered with cleanup pending", outcome is SafCommitOutcome.Committed)
        val committed = outcome as SafCommitOutcome.Committed
        val oldTemporary = requireNotNull(committed.record.temporaryIdentity)
        val final = requireNotNull(committed.record.finalIdentity)
        assertEquals(temporaryUri, oldTemporary.documentUri)
        assertTrue(oldTemporary != final)
        assertEquals(final.documentUri, committed.finalUri)
        assertTrue(committed.record.stagingReleased)
        assertEquals(setOf(SafCleanupPending.PROVIDER_TEMPORARY), committed.pendingCleanup)
        assertTrue(oldTemporary.documentUri in gateway.existing)
        assertEquals(oldTemporary, committed.checkpoint?.pendingCleanup?.single()?.documentIdentity)

        val writes = gateway.countOf("openWrite:")
        val renames = gateway.countOf("rename:")
        gateway.deletionObserved = SafDeletion.ConfirmedAbsent()
        val recovered = coordinator(gateway, staging, journal).coordinator
            .resumeOrReconcile(requireNotNull(committed.checkpoint), grant)

        assertTrue("restart retries only exact cleanup", recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals(0, gateway.countOf("openWrite:") - writes)
        assertEquals(0, gateway.countOf("rename:") - renames)
        assertFalse(oldTemporary.documentUri in gateway.existing)
        assertTrue(staging.deleted)
    }

    @Test
    fun `cancellation before opening performs no provider or staging read and is checkpointed`() {
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal, isCancelled = { true })
            .commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        assertEquals(TransferStorageError.Cancelled, (outcome as SafCommitOutcome.Failed).error)
        assertEquals(SafCommitCheckpointPhase.CANCELLED, outcome.checkpoint?.phase)
        assertEquals("cancelled", outcome.checkpoint?.lastFailure?.categoryId)
        assertEquals(0, staging.readHandles.size)
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.countOf("findChild:"))
        assertEquals(0, gateway.countOf("create:"))
        assertEquals(0, gateway.countOf("openWrite:"))
    }

    @Test
    fun `cancellation during copy preserves exact temp and cleanup retry does not restart transfer`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply { afterStreamWrite = { cancelled = true } }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val firstCoordinator = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator

        val outcome = firstCoordinator.commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        val failed = outcome as SafCommitOutcome.Failed
        assertEquals(TransferStorageError.Cancelled, failed.error)
        val checkpoint = requireNotNull(journal.load(partialId))
        assertEquals(SafCommitCheckpointPhase.CANCELLED, checkpoint.phase)
        assertEquals("cancelled", checkpoint.lastFailure?.categoryId)
        assertNotNull(checkpoint.temporaryIdentity)
        assertFalse(checkpoint.stagingReleased)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(gateway.openedHandles, gateway.closedHandles)
        assertTrue(staging.readHandles.all { it.closeCount == 1 })

        val beforeWrites = gateway.countOf("openWrite:")
        val beforeRenames = gateway.countOf("rename:")
        val recovered = coordinator(gateway, staging, journal, isCancelled = { cancelled })
            .coordinator.resumeOrReconcile(checkpoint, grant)
        assertTrue("the failed-copy result checkpoint is terminal until exact cleanup", recovered is SafCommitRecoveryOutcome.Cancelled)
        val cleaned = coordinator(gateway, staging, journal, isCancelled = { cancelled })
            .coordinator.retryCancelledTemporaryCleanup(checkpoint, grant)

        assertTrue(cleaned is SafCommitRecoveryOutcome.Cancelled)
        assertEquals(beforeWrites, gateway.countOf("openWrite:"))
        assertEquals(beforeRenames, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertFalse(requireNotNull(checkpoint.temporaryIdentity).documentUri in gateway.existing)
        assertFalse(requireNotNull((cleaned as SafCommitRecoveryOutcome.Cancelled).checkpoint).stagingReleased)
    }

    @Test
    fun `cancellation at flush and before verification retains staging and never renames`() {
        listOf("flush", "before_verify").forEach { boundary ->
            var cancelled = false
            val gateway = RecordingSafGateway().apply {
                if (boundary == "flush") afterFlush = { cancelled = true }
            }
            val staging = TrackingStaging(payload)
            val journal = InMemorySafCommitJournal()
            if (boundary == "before_verify") {
                journal.afterSave = { checkpoint ->
                    if (checkpoint.phase == SafCommitCheckpointPhase.FLUSH_COMPLETED) cancelled = true
                }
            }

            val outcome = coordinator(gateway, staging, journal, isCancelled = { cancelled })
                .commit(record(), grant)

            if (boundary == "flush") {
                assertTrue("$boundary: $outcome", outcome is SafCommitOutcome.ReconciliationRequired)
                assertEquals(TransferStorageError.Cancelled, (outcome as SafCommitOutcome.ReconciliationRequired).error)
                assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, journal.load(partialId)?.phase)
                assertNotNull(journal.load(partialId)?.temporaryIdentity)
            } else {
                assertTrue("$boundary: $outcome", outcome is SafCommitOutcome.Failed)
                assertEquals(TransferStorageError.Cancelled, (outcome as SafCommitOutcome.Failed).error)
                assertEquals(SafCommitCheckpointPhase.CANCELLED, journal.load(partialId)?.phase)
            }
            assertEquals(0, gateway.countOf("openRead:"))
            assertEquals(0, gateway.countOf("rename:"))
            assertEquals(0, staging.deleteCalls)
            assertEquals(0, gateway.liveHandles)
        }
    }

    @Test
    fun `cancellation during verification or immediately before rename closes owners and withholds publication`() {
        listOf("verify", "rename") .forEach { boundary ->
            var cancelled = false
            val gateway = RecordingSafGateway()
            val staging = TrackingStaging(payload)
            val journal = InMemorySafCommitJournal()
            if (boundary == "verify") {
                gateway.afterStreamRead = { cancelled = true }
            } else {
                journal.afterSave = { checkpoint ->
                    if (checkpoint.phase == SafCommitCheckpointPhase.PROVIDER_VERIFIED) cancelled = true
                }
            }

            val outcome = coordinator(gateway, staging, journal, isCancelled = { cancelled })
                .commit(record(), grant)

            assertTrue("$boundary: $outcome", outcome is SafCommitOutcome.Failed)
            assertEquals(TransferStorageError.Cancelled, (outcome as SafCommitOutcome.Failed).error)
            assertEquals(SafCommitCheckpointPhase.CANCELLED, journal.load(partialId)?.phase)
            assertEquals(0, gateway.countOf("rename:"))
            assertEquals(0, staging.deleteCalls)
            assertEquals(0, gateway.liveHandles)
            assertEquals(gateway.openedHandles, gateway.closedHandles)
        }
    }

    @Test
    fun `cancellation during rename reconciliation stays unresolved and never retries copy or publishes`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply {
            afterQuery = { if (countOf("rename:") > 0) cancelled = true }
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator

        val outcome = instance.commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val stopped = outcome as SafCommitOutcome.ReconciliationRequired
        assertEquals(TransferStorageError.Cancelled, stopped.error)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, stopped.checkpoint?.phase)
        assertEquals(SafRenamePhase.FINAL_PROMOTION, stopped.checkpoint?.unresolvedRenamePhase)
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        val writes = gateway.countOf("openWrite:")
        val recovery = instance.resumeOrReconcile(requireNotNull(stopped.checkpoint), grant)
        assertTrue(recovery is SafCommitRecoveryOutcome.ReconciliationRequired)
        val cleanup = instance.retryCancelledTemporaryCleanup(requireNotNull(stopped.checkpoint), grant)
        assertTrue(cleanup is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(writes, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `visible final cancellation keeps staging and the exact incomplete visible identity is cleanup-only`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply { afterStreamWrite = { cancelled = true } }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(
            gateway,
            staging,
            journal,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
            isCancelled = { cancelled },
        ).coordinator

        val outcome = instance.commit(record(strategy = SafCommitStrategy.VISIBLE_FINAL_COPY), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        val checkpoint = requireNotNull((outcome as SafCommitOutcome.Failed).checkpoint)
        assertEquals(TransferStorageError.Cancelled, outcome.error)
        assertEquals(SafCommitCheckpointPhase.CANCELLED, checkpoint.phase)
        assertNotNull(checkpoint.finalIdentity)
        assertFalse(checkpoint.stagingReleased)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        val writes = gateway.countOf("openWrite:")
        val cleaned = instance.retryCancelledTemporaryCleanup(checkpoint, grant)
        assertTrue(cleaned is SafCommitRecoveryOutcome.Cancelled)
        assertEquals(writes, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertFalse(checkpoint.finalIdentity!!.documentUri in gateway.existing)
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `visible create result-save failure keeps exact row for cleanup-only recovery`() {
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal().apply {
            rejectNextPhase = SafCommitCheckpointPhase.VISIBLE_CREATED
        }
        val instance = coordinator(
            gateway,
            staging,
            journal,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        ).coordinator

        val first = instance.commit(record(strategy = SafCommitStrategy.VISIBLE_FINAL_COPY), grant)

        assertTrue(first is SafCommitOutcome.ReconciliationRequired)
        val checkpoint = requireNotNull((first as SafCommitOutcome.ReconciliationRequired).checkpoint)
        val interrupted = requireNotNull(checkpoint.finalIdentity)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertTrue(interrupted.documentUri in gateway.existing)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertFalse(first.isDelivered)

        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertNotEquals(interrupted, recovered.checkpoint.finalIdentity)
        assertFalse(interrupted.documentUri in gateway.existing)
        assertEquals(2, gateway.countOf("create:"))
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `visible fallback is explicitly allowed and a fully written copy recovers after result-save failure without recreation`() {
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        journal.rejectNextPhase = SafCommitCheckpointPhase.FLUSH_COMPLETED
        val instance = coordinator(
            gateway,
            staging,
            journal,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        ).coordinator

        val outcome = instance.commit(record(strategy = SafCommitStrategy.VISIBLE_FINAL_COPY), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val checkpoint = requireNotNull((outcome as SafCommitOutcome.ReconciliationRequired).checkpoint)
        assertFalse(outcome.isDelivered)
        val created = checkpoint.finalIdentity
        assertNotNull(created)
        val createCount = gateway.countOf("create:")
        val openWrites = gateway.countOf("openWrite:")
        assertEquals(1, createCount)
        assertTrue(openWrites > 0)

        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue("verified visible copy may be committed after a restart", recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals(createCount, gateway.countOf("create:"))
        assertEquals(openWrites, gateway.countOf("openWrite:"))
        assertTrue(staging.deleted)
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `temporary create result-save failure retains identity and recovery reconciles before retry`() {
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal().apply {
            rejectNextPhase = SafCommitCheckpointPhase.TEMPORARY_CREATED
        }
        val instance = coordinator(gateway, staging, journal).coordinator

        val first = instance.commit(record(), grant)

        assertTrue(first is SafCommitOutcome.ReconciliationRequired)
        val unresolved = first as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(unresolved.checkpoint)
        val interrupted = requireNotNull(checkpoint.temporaryIdentity)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertTrue(interrupted.documentUri in gateway.existing)
        assertEquals(1, gateway.countOf("create:"))
        assertEquals(0, gateway.countOf("openWrite:"))
        assertFalse(staging.deleted)

        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue("recovery should dispose of the exact empty row, then retry", recovered is SafCommitRecoveryOutcome.Committed)
        assertFalse(interrupted.documentUri in gateway.existing)
        assertEquals(2, gateway.countOf("create:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `cancellation after create but before its result checkpoint records exact identity for recovery`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply { afterCreate = { cancelled = true } }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator

        val outcome = instance.commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val reconciliation = outcome as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(reconciliation.checkpoint)
        val created = requireNotNull(checkpoint.temporaryIdentity)
        assertEquals(TransferStorageError.Cancelled, reconciliation.error)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertTrue(created.documentUri in gateway.existing)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)

        cancelled = false
        val recovered = instance.resumeOrReconcile(checkpoint, grant)
        assertTrue("a post-create mutation remains reconciliation-required until exact cleanup", recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        val cleaned = instance.retryCancelledTemporaryCleanup(checkpoint, grant)

        assertTrue(cleaned is SafCommitRecoveryOutcome.Cancelled)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        assertFalse(created.documentUri in gateway.existing)
        assertEquals(1, gateway.countOf("create:"))
        assertFalse(staging.deleted)
    }

    @Test
    fun `cancellation at an unresolved create intent remains reconciliation-required for either strategy`() {
        listOf(
            SafCommitStrategy.TEMP_THEN_RENAME to SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
            SafCommitStrategy.VISIBLE_FINAL_COPY to SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
        ).forEach { (strategy, phase) ->
            val cancelled = true
            val gateway = RecordingSafGateway()
            val staging = TrackingStaging(payload)
            val journal = InMemorySafCommitJournal()
            val instance = SafCommitCoordinator(
                gateway = gateway,
                staging = staging,
                journal = journal,
                allowVisibleFinalCopy = true,
                isCancelled = { cancelled },
            )
            val request = record(strategy = strategy)
            val checkpoint = SafCommitCheckpoint.fromRecord(request, grant, phase)
            assertTrue(journal.save(checkpoint))

            val recovered = instance.resumeOrReconcile(checkpoint, grant)

            assertTrue("$strategy: $recovered", recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
            assertEquals(TransferStorageError.Cancelled, (recovered as SafCommitRecoveryOutcome.ReconciliationRequired).error)
            assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, recovered.checkpoint.phase)
            assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, journal.load(partialId)?.phase)
            assertEquals(0, gateway.countOf("query:"))
            assertEquals(0, gateway.countOf("create:"))
            assertEquals(0, gateway.countOf("openWrite:"))
            assertEquals(0, staging.deleteCalls)
            assertTrue(staging.readHandles.isEmpty())
        }
    }

    @Test
    fun `cancellation after write-open closes both handles and never verifies or renames`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply { afterOpenWrite = { cancelled = true } }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal, isCancelled = { cancelled })
            .commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val reconciliation = outcome as SafCommitOutcome.ReconciliationRequired
        assertEquals(TransferStorageError.Cancelled, reconciliation.error)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, reconciliation.checkpoint?.phase)
        assertNotNull(reconciliation.record.temporaryIdentity)
        assertEquals(1, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("closeWrite:"))
        assertEquals(1, gateway.countOf("closeRead:"))
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, staging.deleteCalls)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `cancellation immediately after rename mutation saves reconciliation before result checkpoint`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply { afterRename = { cancelled = true } }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator

        val outcome = instance.commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        val stopped = outcome as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(stopped.checkpoint)
        assertEquals(TransferStorageError.Cancelled, stopped.error)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertEquals(SafRenamePhase.FINAL_PROMOTION, checkpoint.unresolvedRenamePhase)
        assertNotNull(checkpoint.returnedRenameUri)
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertEquals(0, staging.deleteCalls)
        assertFalse(stopped.isDelivered)
    }

    @Test
    fun `safe-overwrite recovery cancellation during replacement verification preserves backup and both exact identities`() {
        var cancelled = false
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val original = SafStoredDocumentIdentity(documentUri("old-destination"), "old-destination")
        val temporaryUri = documentUri("replacement-temp")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        val replacement = SafStoredDocumentIdentity(temporaryUri, "replacement-temp")
        val backupUri = documentUri("renamed-backup")
        val backupName = backupDocumentName("movie.mp4", partialId)
        val backup = SafStoredDocumentIdentity(backupUri, "renamed-backup")
        gateway.addNamed(temporaryUri, temporaryName)
        gateway.written[temporaryUri] = payload
        gateway.addNamed(backupUri, backupName)
        gateway.written[backupUri] = "old-user-content".toByteArray()
        gateway.afterOpenRead = { cancelled = true }
        val record = record(DuplicatePolicy.OVERWRITE).copy(
            state = SafCommitState.BACKUP_CREATED,
            existingIdentity = original,
            temporaryUri = temporaryUri,
            temporaryIdentity = replacement,
        )
        val checkpoint = SafCommitCheckpoint.fromRecord(
            record,
            grant,
            SafCommitCheckpointPhase.BACKUP_RENAMED,
        ).copy(
            returnedRenameUri = backupUri,
            returnedRenameIdentity = backup,
        )
        assertTrue(journal.save(checkpoint))
        val instance = coordinator(gateway, staging, journal, policy = DuplicatePolicy.OVERWRITE, isCancelled = { cancelled })
            .coordinator

        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Cancelled || recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        val stoppedCheckpoint = when (recovered) {
            is SafCommitRecoveryOutcome.Cancelled -> recovered.checkpoint
            is SafCommitRecoveryOutcome.ReconciliationRequired -> recovered.checkpoint
            else -> error("unexpected recovery outcome: $recovered")
        }
        assertEquals(SafRenamePhase.BACKUP_RENAME, stoppedCheckpoint.unresolvedRenamePhase)
        assertEquals(original, stoppedCheckpoint.existingIdentity)
        assertEquals(replacement, stoppedCheckpoint.temporaryIdentity)
        assertTrue(backupUri in gateway.existing)
        assertTrue(temporaryUri in gateway.existing)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertEquals(1, gateway.countOf("openRead:"))
        assertEquals(1, gateway.countOf("closeRead:"))
        assertEquals(0, staging.deleteCalls)
    }

    @Test
    fun `provider-temporary delete result-save failure recovers by querying the exact identity`() {
        val temporaryUri = documentUri("doc-1")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
            deletionObserved = SafDeletion.StillPresent(info(temporaryUri, "doc-1", temporaryName)),
        )
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal).coordinator
        val committed = instance.commit(record(), grant) as SafCommitOutcome.Committed
        val temporary = requireNotNull(committed.record.temporaryIdentity)
        val deleteCallsBeforeFailure = gateway.countOf("deleteAndReconcile:")

        gateway.deletionObserved = SafDeletion.ConfirmedAbsent()
        journal.rejectNextPhase = SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED
        val failedSave = instance.retryPendingCleanup(committed.record, grant)

        assertTrue(failedSave is SafCommitOutcome.ReconciliationRequired)
        val unresolved = failedSave as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(unresolved.checkpoint)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertEquals(temporary, checkpoint.pendingCleanup.single().documentIdentity)
        assertEquals(deleteCallsBeforeFailure + 1, gateway.countOf("deleteAndReconcile:"))
        assertFalse(temporary.documentUri in gateway.existing)

        val queriesBeforeRecovery = gateway.countOf("query:${temporary.documentUri}")
        val deletesBeforeRecovery = gateway.countOf("deleteAndReconcile:")
        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertTrue(gateway.countOf("query:${temporary.documentUri}") > queriesBeforeRecovery)
        assertEquals("absence observation avoids a duplicate delete", deletesBeforeRecovery, gateway.countOf("deleteAndReconcile:"))
        assertFalse(temporary.documentUri in gateway.existing)
    }

    @Test
    fun `backup delete result-save failure recovers through exact absence without a second delete`() {
        val gateway = RecordingSafGateway().apply {
            grantFailureOn = { operation ->
                if (operation == SafContainmentOperation.DELETE_TEMPORARY) {
                    TransferStorageError.PermissionRevoked("defer backup cleanup")
                } else {
                    null
                }
            }
        }
        seedOverwriteTarget(gateway)
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(
            gateway,
            staging,
            journal,
            policy = DuplicatePolicy.OVERWRITE,
        ).coordinator
        val committed = instance.commit(record(DuplicatePolicy.OVERWRITE), grant) as SafCommitOutcome.Committed
        val backup = requireNotNull(committed.record.backupIdentity)
        assertEquals(setOf(SafCleanupPending.BACKUP), committed.pendingCleanup)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))

        gateway.grantFailureOn = null
        journal.rejectNextPhase = SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED
        val failedSave = instance.retryPendingCleanup(committed.record, grant)

        assertTrue(failedSave is SafCommitOutcome.ReconciliationRequired)
        val unresolved = failedSave as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(unresolved.checkpoint)
        assertEquals(backup, checkpoint.pendingCleanup.single().documentIdentity)
        assertFalse(backup.documentUri in gateway.existing)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))

        val queriesBeforeRecovery = gateway.countOf("query:${backup.documentUri}")
        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertTrue(gateway.countOf("query:${backup.documentUri}") > queriesBeforeRecovery)
        assertEquals("exact absence avoids repeating backup deletion", 1, gateway.countOf("deleteAndReconcile:"))
        assertFalse(backup.documentUri in gateway.existing)
    }

    @Test
    fun `staging delete result-save failure recovers without repeating the local deletion`() {
        val gateway = RecordingSafGateway()
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal().apply {
            rejectNextPhase = SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED
        }
        val instance = coordinator(gateway, staging, journal).coordinator

        val first = instance.commit(record(), grant)

        assertTrue(first is SafCommitOutcome.ReconciliationRequired)
        assertEquals(1, staging.deleteCalls)
        assertTrue(staging.deleted)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, journal.load(partialId)?.phase)
        val writes = gateway.countOf("openWrite:")
        val renames = gateway.countOf("rename:")

        val recovered = instance.resumeOrReconcile(requireNotNull(journal.load(partialId)), grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals("confirmed staging absence prevents another delete", 1, staging.deleteCalls)
        assertEquals(writes, gateway.countOf("openWrite:"))
        assertEquals(renames, gateway.countOf("rename:"))
    }

    @Test
    fun `cancellation after provider delete before its result checkpoint reconciles through exact identity`() {
        val temporaryUri = documentUri("doc-1")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        var cancelled = false
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
            deletionObserved = SafDeletion.StillPresent(info(temporaryUri, "doc-1", temporaryName)),
        )
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator
        val committed = instance.commit(record(), grant) as SafCommitOutcome.Committed
        val temporary = requireNotNull(committed.record.temporaryIdentity)
        gateway.deletionObserved = SafDeletion.ConfirmedAbsent()
        gateway.afterDelete = { cancelled = true }

        val stopped = instance.retryPendingCleanup(committed.record, grant)

        assertTrue(stopped is SafCommitOutcome.ReconciliationRequired)
        val reconciliation = stopped as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(reconciliation.checkpoint)
        assertEquals(TransferStorageError.Cancelled, reconciliation.error)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertEquals(temporary, checkpoint.pendingCleanup.single().documentIdentity)
        assertFalse(temporary.documentUri in gateway.existing)

        cancelled = false
        val deleteCount = gateway.countOf("deleteAndReconcile:")
        val queries = gateway.countOf("query:${temporary.documentUri}")
        val recovered = instance.retryPendingCleanup(reconciliation.record, grant)

        assertTrue(recovered is SafCommitOutcome.Committed)
        assertEquals("confirmed absence prevents repeating the delete", deleteCount, gateway.countOf("deleteAndReconcile:"))
        assertTrue(gateway.countOf("query:${temporary.documentUri}") > queries)
    }

    @Test
    fun `cancelled temporary cleanup result-save failure recovers through exact absence observation`() {
        var cancelled = false
        val gateway = RecordingSafGateway().apply {
            afterCreate = { cancelled = true }
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, isCancelled = { cancelled }).coordinator
        val interrupted = instance.commit(record(), grant) as SafCommitOutcome.ReconciliationRequired
        val checkpoint = requireNotNull(interrupted.checkpoint)
        val temporary = requireNotNull(interrupted.record.temporaryIdentity)

        cancelled = false
        gateway.afterDelete = { cancelled = true }
        journal.rejectNextPhase = SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED
        val failedSave = instance.retryCancelledTemporaryCleanup(checkpoint, grant)

        assertTrue(failedSave is SafCommitRecoveryOutcome.ReconciliationRequired)
        val intent = (failedSave as SafCommitRecoveryOutcome.ReconciliationRequired).checkpoint
        assertEquals(SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT, intent.phase)
        assertFalse(temporary.documentUri in gateway.existing)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))

        val queriesBeforeRecovery = gateway.countOf("query:${temporary.documentUri}")
        val recovered = instance.resumeOrReconcile(intent, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Cancelled)
        assertEquals(SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED, recovered.checkpoint.phase)
        assertEquals("exact absence prevents a duplicate delete", 1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(gateway.countOf("query:${temporary.documentUri}") > queriesBeforeRecovery)
    }

    @Test
    fun `cancellation after safe-overwrite backup deletion reconciles and resumes from exact absence`() {
        var cancelled = false
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            deletionObserved = SafDeletion.ConfirmedAbsent(),
        ).apply {
            afterDelete = { cancelled = true }
        }
        seedOverwriteTarget(gateway)
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(
            gateway,
            staging,
            journal,
            policy = DuplicatePolicy.OVERWRITE,
            isCancelled = { cancelled },
        ).coordinator

        val stopped = instance.commit(record(DuplicatePolicy.OVERWRITE), grant)

        assertTrue(stopped is SafCommitOutcome.ReconciliationRequired)
        val reconciliation = stopped as SafCommitOutcome.ReconciliationRequired
        assertEquals(TransferStorageError.Cancelled, reconciliation.error)
        val checkpoint = requireNotNull(reconciliation.checkpoint)
        val backup = requireNotNull(checkpoint.backupIdentity)
        val final = requireNotNull(checkpoint.finalIdentity)
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertFalse(backup.documentUri in gateway.existing)
        assertTrue(final.documentUri in gateway.existing)
        assertFalse(staging.deleted)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        val unsafeCancelledCleanup = instance.retryCancelledTemporaryCleanup(checkpoint, grant)
        assertTrue(unsafeCancelledCleanup is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals("published final must not be deleted as a cancelled temporary", 1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(final.documentUri in gateway.existing)

        cancelled = false
        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals("absence observation prevents deleting the backup twice", 1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `safe overwrite retains both the old backup and provider temporary until the new final is proven`() {
        val temporaryUri = documentUri("doc-1")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        var renameCount = 0
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            deletionObserved = SafDeletion.StillPresent(info(temporaryUri, "doc-1", temporaryName)),
        ).apply {
            afterRename = {
                renameCount++
                if (renameCount == 1) renameKeepsOriginal = true
            }
        }
        val (oldUri, oldBytes) = seedOverwriteTarget(gateway)
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal, policy = DuplicatePolicy.OVERWRITE).coordinator

        val outcome = instance.commit(record(DuplicatePolicy.OVERWRITE), grant)

        assertTrue(outcome is SafCommitOutcome.Committed)
        val committed = outcome as SafCommitOutcome.Committed
        val backup = requireNotNull(committed.record.backupIdentity)
        val temporary = requireNotNull(committed.record.temporaryIdentity)
        val final = requireNotNull(committed.record.finalIdentity)
        assertTrue(committed.isDelivered)
        assertNotEquals(oldUri, final.documentUri)
        assertTrue(backup.documentUri in gateway.existing)
        assertTrue(temporary.documentUri in gateway.existing)
        assertTrue(gateway.written[backup.documentUri]!!.contentEquals(oldBytes))
        assertTrue(gateway.written[final.documentUri]!!.contentEquals(payload))
        assertEquals(
            setOf(SafCleanupPending.BACKUP, SafCleanupPending.PROVIDER_TEMPORARY),
            committed.pendingCleanup,
        )
        assertEquals(
            setOf(backup, temporary),
            committed.checkpoint!!.pendingCleanup.mapNotNull { it.documentIdentity }.toSet(),
        )

        val creates = gateway.countOf("create:")
        val writes = gateway.countOf("openWrite:")
        val renames = gateway.countOf("rename:")
        gateway.deletionObserved = SafDeletion.ConfirmedAbsent()
        val recovered = instance.retryPendingCleanup(committed.record, grant)

        assertTrue(recovered is SafCommitOutcome.Committed)
        assertTrue((recovered as SafCommitOutcome.Committed).pendingCleanup.isEmpty())
        assertEquals(creates, gateway.countOf("create:"))
        assertEquals(writes, gateway.countOf("openWrite:"))
        assertEquals(renames, gateway.countOf("rename:"))
        assertFalse(backup.documentUri in gateway.existing)
        assertFalse(temporary.documentUri in gateway.existing)
        assertTrue(final.documentUri in gateway.existing)
        assertTrue(staging.deleted)
    }

    @Test
    fun `visible partial copy recovery deletes the exact incomplete row before creating a complete final`() {
        val gateway = RecordingSafGateway().apply {
            partialWriteFailureAfterBytes = 2L
            partialWriteFailure = IOException("injected interruption")
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(
            gateway,
            staging,
            journal,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        ).coordinator
        val first = instance.commit(record(strategy = SafCommitStrategy.VISIBLE_FINAL_COPY), grant)

        assertTrue(first is SafCommitOutcome.Failed || first is SafCommitOutcome.ReconciliationRequired)
        assertFalse(first.isDelivered)
        val checkpoint = requireNotNull(journal.load(partialId))
        val incomplete = requireNotNull(checkpoint.finalIdentity)
        assertEquals(2, gateway.written[incomplete.documentUri]?.size)
        assertFalse(staging.deleted)

        val recovered = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        val final = requireNotNull(journal.load(partialId)?.finalIdentity)
        assertNotEquals("incomplete visible identity must never be committed", incomplete, final)
        assertFalse(incomplete.documentUri in gateway.existing)
        assertTrue(gateway.written[final.documentUri]!!.contentEquals(payload))
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        assertEquals(2, gateway.countOf("create:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `interrupted temporary delete result-save failure recovers through exact absence before retry`() {
        val temporaryUri = documentUri("interrupted-temporary")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        val gateway = RecordingSafGateway().apply {
            addNamed(temporaryUri, temporaryName)
            written[temporaryUri] = "partial".toByteArray()
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal).coordinator
        val interrupted = record().copy(
            state = SafCommitState.PROVIDER_VERIFIED,
            temporaryUri = temporaryUri,
            temporaryIdentity = SafStoredDocumentIdentity(temporaryUri, "interrupted-temporary"),
        )
        val checkpoint = SafCommitCheckpoint.fromRecord(
            interrupted,
            grant,
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
        )
        assertTrue(journal.save(checkpoint))
        journal.rejectNextPhase = SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED

        val failedSave = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(failedSave is SafCommitRecoveryOutcome.ReconciliationRequired)
        val intent = (failedSave as SafCommitRecoveryOutcome.ReconciliationRequired).checkpoint
        assertEquals(SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT, intent.phase)
        assertFalse(temporaryUri in gateway.existing)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        val queriesBeforeRecovery = gateway.countOf("query:$temporaryUri")

        val recovered = instance.resumeOrReconcile(intent, grant)

        assertTrue("absence is checkpointed before restarting the verified copy", recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals("exact absence avoids a second delete", 1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(gateway.countOf("query:$temporaryUri") > queriesBeforeRecovery)
        assertEquals(SafCommitCheckpointPhase.COMMITTED, journal.load(partialId)?.phase)
        val deleteObserved = journal.writes.indexOfLast {
            it.phase == SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED
        }
        val readyAgain = journal.writes.indexOfLast { it.phase == SafCommitCheckpointPhase.READY }
        assertTrue("delete observation must be saved before restarting", deleteObserved in 0 until readyAgain)
        assertTrue(staging.deleted)
    }

    @Test
    fun `interrupted visible-final delete result-save failure observes absence before recreating`() {
        val incompleteUri = documentUri("interrupted-visible-final")
        val gateway = RecordingSafGateway().apply {
            addNamed(incompleteUri, "movie.mp4")
            written[incompleteUri] = "partial".toByteArray()
        }
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(
            gateway,
            staging,
            journal,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        ).coordinator
        val incomplete = record(strategy = SafCommitStrategy.VISIBLE_FINAL_COPY).copy(
            state = SafCommitState.FINAL_CREATED,
            finalUri = incompleteUri,
            finalIdentity = SafStoredDocumentIdentity(incompleteUri, "interrupted-visible-final"),
        )
        val checkpoint = SafCommitCheckpoint.fromRecord(
            incomplete,
            grant,
            SafCommitCheckpointPhase.VISIBLE_CREATED,
        )
        assertTrue(journal.save(checkpoint))
        journal.rejectNextPhase = SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED

        val failedSave = instance.resumeOrReconcile(checkpoint, grant)

        assertTrue(failedSave is SafCommitRecoveryOutcome.ReconciliationRequired)
        val intent = (failedSave as SafCommitRecoveryOutcome.ReconciliationRequired).checkpoint
        assertEquals(SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT, intent.phase)
        assertFalse(incompleteUri in gateway.existing)
        assertEquals(1, gateway.countOf("deleteAndReconcile:"))
        val queriesBeforeRecovery = gateway.countOf("query:$incompleteUri")

        val recovered = instance.resumeOrReconcile(intent, grant)

        assertTrue("exact absence is checkpointed before a complete visible copy is made", recovered is SafCommitRecoveryOutcome.Committed)
        val final = requireNotNull(journal.load(partialId)?.finalIdentity)
        assertNotEquals("the incomplete visible identity is never committed", incomplete.finalIdentity, final)
        assertFalse(incompleteUri in gateway.existing)
        assertTrue(final.documentUri in gateway.existing)
        assertEquals(1, gateway.countOf("create:"))
        assertEquals("absence prevents a second delete", 1, gateway.countOf("deleteAndReconcile:"))
        assertTrue(gateway.countOf("query:$incompleteUri") > queriesBeforeRecovery)
        val deleteObserved = journal.writes.indexOfLast { it.phase == SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED }
        val readyAgain = journal.writes.indexOfLast { it.phase == SafCommitCheckpointPhase.READY }
        assertTrue("delete observation must be saved before recreating", deleteObserved in 0 until readyAgain)
        assertTrue(staging.deleted)
    }

    @Test
    fun `integrated coordinator streams and verifies a virtual five GiB destination with Long accounting`() {
        val expectedSize = 5_368_709_120L
        val staging = VirtualStaging(expectedSize)
        val gateway = VirtualSafGateway(::documentUri)
        val journal = InMemorySafCommitJournal()
        val instance = SafCommitCoordinator(
            gateway = gateway,
            staging = staging,
            journal = journal,
            verificationDigesterFactory = virtualDigesterFactory(),
        )
        val source = record().copy(expectedSizeBytes = expectedSize)

        val outcome = instance.commit(source, grant)

        assertTrue("virtual large commit failed: $outcome", outcome is SafCommitOutcome.Committed)
        val committed = outcome as SafCommitOutcome.Committed
        assertEquals(expectedSize, committed.record.copiedBytes)
        assertEquals(expectedSize, committed.checkpoint?.expectedSizeBytes)
        assertEquals(expectedSize, committed.checkpoint?.copiedBytes)
        assertTrue(expectedSize > Int.MAX_VALUE.toLong())
        assertEquals(expectedSize, gateway.onlyRow()?.sizeBytes)
        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.openWriteCount)
        assertEquals("temporary and final bytes are each freshly verified", 2, gateway.openReadCount)
        assertEquals(Sha256Digest.fromHex("00".repeat(32)), committed.checkpoint?.verifiedDigest)
        assertTrue(staging.deleted)
    }

    @Test
    fun `cleanup permission revocation keeps the exact identity pending and typed`() {
        val temporaryUri = documentUri("doc-1")
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
            deletionObserved = SafDeletion.StillPresent(info(temporaryUri, "doc-1", temporaryName)),
        )
        val staging = TrackingStaging(payload)
        val journal = InMemorySafCommitJournal()
        val instance = coordinator(gateway, staging, journal).coordinator
        val committed = instance.commit(record(), grant) as SafCommitOutcome.Committed
        val temporary = requireNotNull(committed.record.temporaryIdentity)
        val deleteCalls = gateway.countOf("deleteAndReconcile:")
        gateway.grantFailureOn = { operation ->
            if (operation == SafContainmentOperation.DELETE_TEMPORARY) {
                TransferStorageError.PermissionRevoked("cleanup")
            } else {
                null
            }
        }

        val retried = instance.retryPendingCleanup(committed.record, grant)

        assertTrue(retried is SafCommitOutcome.Committed)
        val pending = retried as SafCommitOutcome.Committed
        assertEquals(setOf(SafCleanupPending.PROVIDER_TEMPORARY), pending.pendingCleanup)
        assertEquals(temporary, pending.checkpoint?.pendingCleanup?.single()?.documentIdentity)
        assertEquals(deleteCalls, gateway.countOf("deleteAndReconcile:"))
        assertTrue(temporary.documentUri in gateway.existing)
        assertEquals("permission_revoked", pending.checkpoint?.lastFailure?.categoryId)
    }
}
