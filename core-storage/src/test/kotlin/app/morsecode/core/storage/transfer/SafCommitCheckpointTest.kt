package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.PartialIdentity
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafCommitCheckpointTest {

    private val grant = SafTreeGrant(
        grantId = "grant-1",
        treeUri = Uri.parse("content://provider/tree/root"),
        rootDocumentId = "root",
        authority = "provider",
        writable = true,
    )

    private fun record() = SafCommitRecord(
        sessionId = SessionId("session-1"),
        transferId = TransferId("transfer-1"),
        partialId = PartialIdentity("partial-1"),
        treeUri = grant.treeUri.toString(),
        rootDocumentId = grant.rootDocumentId,
        parentDocumentId = grant.rootDocumentId,
        expectedFinalName = "private-name-🚀.mp4",
        expectedSizeBytes = 5_368_709_120L,
        grantId = grant.grantId,
        strategy = SafCommitStrategy.TEMP_THEN_RENAME,
        duplicatePolicy = DuplicatePolicy.OVERWRITE,
    )

    @Test
    fun `checkpoint captures versioned identities and long lengths without handles`() {
        val source = record()
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                temporaryUri = "content://provider/document/temp",
                temporaryIdentity = SafStoredDocumentIdentity(
                    "content://provider/document/temp",
                    "opaque-temp-id",
                ),
                copiedBytes = 5_368_709_120L,
                state = SafCommitState.COPY_COMPLETED,
            ),
            grant,
            SafCommitCheckpointPhase.COPY_COMPLETED,
        )

        assertEquals(SafCommitCheckpoint.CURRENT_VERSION, checkpoint.version)
        assertEquals(SessionId("session-1"), checkpoint.sessionId)
        assertEquals(TransferId("transfer-1"), checkpoint.transferId)
        assertEquals(PartialIdentity("partial-1"), checkpoint.commitId)
        assertEquals(PartialIdentity("partial-1"), checkpoint.stagingIdentity)
        assertEquals(5_368_709_120L, checkpoint.expectedSizeBytes)
        assertEquals(5_368_709_120L, checkpoint.copiedBytes)
        assertEquals("root", checkpoint.parentDocumentId)
        assertEquals("grant-1", checkpoint.approvedTree.grantId)
        assertEquals("opaque-temp-id", checkpoint.temporaryIdentity?.documentId)
        assertEquals(SafCommitCheckpointPhase.COPY_COMPLETED, checkpoint.phase)
    }

    @Test
    fun `checkpoint diagnostic string does not expose names uris ids or digest material`() {
        val digest = Sha256Accumulator().apply { update("distinct-checkpoint-digest".toByteArray()) }.digest()
        val checkpoint = SafCommitCheckpoint.fromRecord(record().copy(expectedDigest = digest), grant)
        val diagnostic = checkpoint.toString() + checkpoint.approvedTree.toString()
        listOf(
            "private-name",
            "content://provider",
            "grant-1",
            "root",
            "partial-1",
            digest.hex,
            digest.toString(),
        ).forEach { secret -> assertFalse(diagnostic.contains(secret)) }
    }

    @Test
    fun `staging release remains independent while provider cleanup is pending`() {
        val temporary = SafStoredDocumentIdentity("content://provider/document/temp", "temp")
        val final = SafStoredDocumentIdentity("content://provider/document/final", "final")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            record().copy(
                temporaryUri = temporary.documentUri,
                temporaryIdentity = temporary,
                finalUri = final.documentUri,
                finalIdentity = final,
                pendingCleanup = setOf(SafCleanupPending.PROVIDER_TEMPORARY),
                stagingReleased = true,
            ),
            grant,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
        )

        assertTrue(checkpoint.stagingReleased)
        assertEquals(listOf(SafCleanupPending.PROVIDER_TEMPORARY), checkpoint.pendingCleanup.map { it.type })
    }

    @Test
    fun `pending provider cleanup requires its exact document identity`() {
        assertTrue(runCatching {
            SafPendingCleanupIdentity(type = SafCleanupPending.PROVIDER_TEMPORARY)
        }.isFailure)
        val identity = SafStoredDocumentIdentity("content://provider/document/1", "doc-1")
        val pending = SafPendingCleanupIdentity(
            type = SafCleanupPending.PROVIDER_TEMPORARY,
            documentIdentity = identity,
        )
        assertEquals(identity, pending.documentIdentity)
        assertNull(pending.stagingIdentity)
    }

    @Test
    fun `checkpoint version remains explicit and unknown phases do not parse as known`() {
        assertEquals(SafCommitCheckpoint.CURRENT_VERSION, SafCommitCheckpoint.fromRecord(record(), grant).version)
        assertNull(SafCommitCheckpointPhase.fromId("future-phase"))
        assertEquals(
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            SafCommitCheckpoint.fromRecord(record(), grant).copy(
                phase = SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            ).phase,
        )
    }

    @Test
    fun `test journal saves and loads exact checkpoints and can refuse a write`() {
        val journal = InMemorySafCommitJournal()
        val checkpoint = SafCommitCheckpoint.fromRecord(record(), grant)
        assertTrue(journal.save(checkpoint))
        assertEquals(checkpoint, journal.load(checkpoint.commitId))

        journal.rejectNextSave = true
        val updated = checkpoint.copy(phase = SafCommitCheckpointPhase.COPY_STARTED)
        assertFalse(journal.save(updated))
        assertEquals(checkpoint, journal.load(checkpoint.commitId))
    }
}
