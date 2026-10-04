package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.TransferId
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The states a commit can be left in, and which of them is allowed to advance.
 *
 * The governing rule is that an unknown is not a success. Every case below that
 * ends in reconciliation rather than a commit is a case where the provider did
 * not say what exists, and the commit stops rather than picking the most likely
 * answer -- because in each of them a guess that is wrong costs the user a file.
 *
 * A rename that returns a new identity is the sharpest example. The old code took
 * `renamed.documentUri ?: created.documentUri`, which on a provider that renames
 * by copying leaves the commit pointing at the temporary and the final name
 * either absent or holding something else. Both identities are preserved here
 * instead and the next pass is told to ask.
 */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitReconciliationTest {

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private val parentDocumentId = "primary:Download"

    private val grant = SafTreeGrant(
        grantId = "g-1",
        treeUri = Uri.parse(treeUri),
        rootDocumentId = parentDocumentId,
        authority = requireNotNull(Uri.parse(treeUri).authority),
        writable = true,
    )

    private val payload = "morsec-payload".toByteArray()

    private fun commit(
        gateway: RecordingSafGateway,
        policy: DuplicatePolicy = DuplicatePolicy.RENAME,
        strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,
        allowVisibleFinalCopy: Boolean = false,
        releasesStaging: Boolean = true,
        parentOverride: String = parentDocumentId,
    ): SafCommitOutcome {
        val staging = ByteArrayStaging(payload, releases = releasesStaging)
        val coordinator = SafCommitCoordinator(
            gateway = gateway,
            staging = staging,
            allowVisibleFinalCopy = allowVisibleFinalCopy,
        )
        return coordinator.commit(
            SafCommitRecord(
                transferId = TransferId("t-1"),
                partialId = PartialIdentity("p-1"),
                treeUri = treeUri,
                rootDocumentId = parentDocumentId,
                parentDocumentId = parentOverride,
                expectedFinalName = "movie.mp4",
                expectedSizeBytes = payload.size.toLong(),
                strategy = strategy,
                duplicatePolicy = policy,
            ),
            grant,
        )
    }

    private class ByteArrayStaging(
        private val bytes: ByteArray,
        private val releases: Boolean = true,
    ) : SafStaging {
        override fun length(identity: PartialIdentity): Long? = bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))

        override fun delete(identity: PartialIdentity): Boolean = releases
    }

    // -- Rename reconciliation ----------------------------------------------

    @Test
    fun `a rename that hands back a new identity adopts that identity`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.NEW)
        val outcome = commit(gateway)
        val committed = outcome as SafCommitOutcome.Committed
        assertTrue("rename must not fall back to the pre-rename URI", committed.finalUri != gateway.lastCreatedUri)
        assertEquals("doc-2", committed.finalUri.substringAfterLast('/'))
    }

    @Test
    fun `a rename that hands back the same identity is still resolved`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.SAME)
        val outcome = commit(gateway)
        assertTrue(outcome is SafCommitOutcome.Committed)
    }

    @Test
    fun `a rename that hands back nothing stops for reconciliation`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.NULL)
        val outcome = commit(gateway)
        val pending = outcome as SafCommitOutcome.ReconciliationRequired
        assertEquals(
            "the temporary is the only identity known, and it is preserved",
            listOf(gateway.lastCreatedUri),
            pending.knownUris,
        )
    }

    @Test
    fun `a rename that copies rather than moves preserves both identities`() {
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
        )
        val outcome = commit(gateway)
        val pending = outcome as SafCommitOutcome.ReconciliationRequired
        assertEquals(
            "both identities must survive for the next pass to disambiguate",
            2,
            pending.knownUris.size,
        )
        assertFalse(outcome.isDelivered)
    }

    @Test
    fun `a rename whose returned identity does not resolve stops for reconciliation`() {
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameDropsReturned = true,
        )
        assertTrue(commit(gateway) is SafCommitOutcome.ReconciliationRequired)
    }

    @Test
    fun `a rename that leaves the original and drops the returned one stops too`() {
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
            renameDropsReturned = true,
        )
        assertTrue(commit(gateway) is SafCommitOutcome.ReconciliationRequired)
    }

    @Test
    fun `a query that will not answer during reconciliation is not a commit`() {
        val gateway = RecordingSafGateway()
        var queries = 0
        gateway.throwOn = { op ->
            if (op == RecordingSafGateway.OP_QUERY && ++queries > 1) {
                IllegalStateException("provider is not answering")
            } else {
                null
            }
        }
        val outcome = commit(gateway)
        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertFalse(outcome.isDelivered)
    }

    @Test
    fun `a revoked grant during reconciliation is PermissionRevoked, not Missing`() {
        val gateway = RecordingSafGateway()
        var queries = 0
        gateway.throwOn = { op ->
            if (op == RecordingSafGateway.OP_QUERY && ++queries > 1) SecurityException("revoked") else null
        }
        val outcome = commit(gateway) as SafCommitOutcome.ReconciliationRequired
        assertTrue(
            "a SecurityException must never be downgraded to Missing",
            outcome.error is TransferStorageError.PermissionRevoked,
        )
    }

    @Test
    fun `a parent outside the proved tree is refused as ContainmentUnknown`() {
        val gateway = RecordingSafGateway()

        val outcome = commit(gateway, parentOverride = "$parentDocumentId/opaque-child")

        val pending = outcome as SafCommitOutcome.ReconciliationRequired
        assertTrue(pending.error is TransferStorageError.ContainmentUnknown)
        assertEquals(0, gateway.countOf("findChild:"))
        assertEquals(0, gateway.countOf("create:"))
    }

    @Test
    fun `a grant revoked before destination lookup is typed and performs no provider lookup`() {
        val gateway = RecordingSafGateway()
        gateway.grantFailureOn = { operation ->
            if (operation == SafContainmentOperation.RECONCILE) {
                TransferStorageError.PermissionRevoked("read")
            } else {
                null
            }
        }

        val outcome = commit(gateway)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue(
            (outcome as SafCommitOutcome.ReconciliationRequired).error is
                TransferStorageError.PermissionRevoked,
        )
        assertEquals(0, gateway.countOf("findChild:"))
    }

    @Test
    fun `a grant revoked before opening for write closes staging and prevents the owner opening`() {
        val gateway = RecordingSafGateway()
        gateway.grantFailureOn = { operation ->
            if (operation == SafContainmentOperation.OPEN_WRITE) {
                TransferStorageError.PermissionRevoked("write")
            } else {
                null
            }
        }

        val outcome = commit(gateway)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertTrue(
            (outcome as SafCommitOutcome.ReconciliationRequired).error is
                TransferStorageError.PermissionRevoked,
        )
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.liveHandles)
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `grant revocation between provider size query and fresh read prevents verification`() {
        val gateway = RecordingSafGateway()
        var verifyChecks = 0
        gateway.grantFailureOn = { operation ->
            if (operation == SafContainmentOperation.VERIFY && ++verifyChecks == 2) {
                TransferStorageError.PermissionRevoked("read")
            } else {
                null
            }
        }

        val outcome = commit(gateway)

        val pending = outcome as SafCommitOutcome.ReconciliationRequired
        assertTrue(pending.error is TransferStorageError.PermissionRevoked)
        assertEquals("the second check is immediately before openRead", 2, verifyChecks)
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `a collision query failure is not treated as a free rename candidate`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        var lookups = 0
        gateway.throwOn = { operation ->
            if (operation == RecordingSafGateway.OP_FIND_CHILD && ++lookups == 2) {
                IllegalStateException("collision list unavailable")
            } else {
                null
            }
        }

        val outcome = commit(gateway, policy = DuplicatePolicy.RENAME)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertEquals(0, gateway.countOf("create:"))
    }

    // -- Duplicate policies --------------------------------------------------

    @Test
    fun `skip creates nothing and does not claim delivery`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        val outcome = commit(gateway, policy = DuplicatePolicy.SKIP)
        assertTrue(outcome is SafCommitOutcome.Skipped)
        assertEquals(0, gateway.countOf("create:"))
        assertFalse(outcome.isDelivered)
    }

    @Test
    fun `ask creates nothing and waits`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        val outcome = commit(gateway, policy = DuplicatePolicy.ASK)
        assertTrue(outcome is SafCommitOutcome.PendingUserDecision)
        assertEquals(0, gateway.countOf("create:"))
        assertFalse(outcome.isDelivered)
    }

    @Test
    fun `rename does not overwrite the existing document`() {
        val gateway = RecordingSafGateway()
        val existingUri = "$treeUri/document/seed"
        gateway.addNamed(existingUri, "movie.mp4")
        val outcome = commit(gateway, policy = DuplicatePolicy.RENAME)
        assertTrue(outcome is SafCommitOutcome.Committed)
        assertTrue("the existing document must still be there", existingUri in gateway.existing)
    }

    // -- Recoverable overwrite -----------------------------------------------

    @Test
    fun `overwrite never deletes the existing document before replacing it`() {
        val gateway = RecordingSafGateway()
        val existingUri = "$treeUri/document/seed"
        gateway.addNamed(existingUri, "movie.mp4")
        val outcome = commit(gateway, policy = DuplicatePolicy.OVERWRITE)
        assertTrue(outcome is SafCommitOutcome.Committed)

        // A provider can preserve the URI across a rename. Therefore URI
        // inequality cannot prove we avoided delete-first; order does. The
        // original is renamed to the backup name, the replacement is promoted,
        // final content is reopened and verified, and only then is the backup
        // identity deleted.
        val backupRename = gateway.calls.indexOf(
            "rename:$existingUri:${backupDocumentName("movie.mp4", PartialIdentity("p-1"))}",
        )
        val replacementRename = gateway.calls.indexOf(
            "rename:${gateway.lastCreatedUri}:movie.mp4",
        )
        val finalVerificationClose = gateway.calls.indexOfLast {
            it.startsWith("closeRead:${gateway.lastCreatedUri}")
        }
        val backupDelete = gateway.calls.indexOfFirst { it.startsWith("deleteAndReconcile:") }
        assertTrue("the existing document was moved aside", backupRename >= 0)
        assertTrue("the replacement was promoted", replacementRename >= 0)
        assertTrue("the backup move preceded the replacement promotion", backupRename < replacementRename)
        assertTrue("final verification followed promotion", replacementRename < finalVerificationClose)
        assertTrue("backup removal followed the replacement promotion", replacementRename < backupDelete)
        assertTrue("backup removal followed final verification", finalVerificationClose < backupDelete)
    }

    @Test
    fun `overwrite delivers under the final name`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        val outcome = commit(gateway, policy = DuplicatePolicy.OVERWRITE)
        val committed = outcome as SafCommitOutcome.Committed
        assertTrue(gateway.calls.any { it.startsWith("deleteAndReconcile:") })
        assertTrue(committed.cleanupComplete)
    }

    @Test
    fun `an overwrite under the visible-copy strategy is refused, not attempted`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        val outcome = commit(
            gateway,
            policy = DuplicatePolicy.OVERWRITE,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        )
        val refused = outcome as SafCommitOutcome.SafeOverwriteUnsupported
        assertEquals("$treeUri/document/seed", refused.existingUri)
        assertEquals(0, gateway.countOf("create:"))
    }

    @Test
    fun `an overwrite whose backup stays present is delivered with cleanup pending`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        gateway.deletionObserved = SafDeletion.StillPresent(
            document = SafDocumentInfo(
                documentUri = "$treeUri/document/seed",
                documentId = "seed",
                displayName = backupDocumentName("movie.mp4", PartialIdentity("p-1")),
                sizeBytes = 0L,
                mimeType = null,
                flags = 0,
                isDirectory = false,
            ),
        )
        val outcome = commit(gateway, policy = DuplicatePolicy.OVERWRITE)
        val committed = outcome as SafCommitOutcome.Committed
        assertTrue(
            "the backup is outstanding",
            SafCleanupPending.BACKUP in committed.pendingCleanup,
        )
        assertFalse(committed.cleanupComplete)
        assertTrue("the file itself is delivered", committed.isDelivered)
    }

    @Test
    fun `an overwrite whose backup cannot be queried stops for reconciliation`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        gateway.deletionObserved = SafDeletion.QueryUnknown("provider would not answer")
        val outcome = commit(gateway, policy = DuplicatePolicy.OVERWRITE)
        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
    }

    // -- Cleanup pending ------------------------------------------------------

    @Test
    fun `staging that will not delete leaves the commit delivered but pending`() {
        val gateway = RecordingSafGateway()
        val outcome = commit(gateway, releasesStaging = false)
        val committed = outcome as SafCommitOutcome.Committed
        assertTrue(SafCleanupPending.STAGING in committed.pendingCleanup)
        assertFalse(committed.stagingReleased)
        assertFalse(committed.cleanupComplete)
        assertTrue("delivery is not undone by a failed cleanup", committed.isDelivered)
        assertEquals(
            "correct content is never recopied because cleanup failed",
            1,
            gateway.countOf("openWrite:"),
        )
    }

    @Test
    fun `a clean commit reports nothing pending`() {
        val gateway = RecordingSafGateway()
        val committed = commit(gateway) as SafCommitOutcome.Committed
        assertTrue(committed.pendingCleanup.isEmpty())
        assertTrue(committed.cleanupComplete)
        assertTrue(committed.stagingReleased)
    }

    // -- Storage full ---------------------------------------------------------

    @Test
    fun `storage filling up during the copy is typed as StorageFull`() {
        val gateway = RecordingSafGateway()
        gateway.throwOn = { op ->
            if (op == RecordingSafGateway.OP_OPEN_WRITE) RecordingSafGateway.storageFull() else null
        }
        val outcome = commit(gateway)
        val failed = outcome as SafCommitOutcome.Failed
        assertTrue(
            "a full medium must be classified, not reported as ordinary IO",
            failed.error is TransferStorageError.StorageFull,
        )
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `storage filling up at create time is typed as StorageFull too`() {
        val gateway = RecordingSafGateway()
        gateway.throwOn = { op ->
            if (op == RecordingSafGateway.OP_CREATE) RecordingSafGateway.storageFull() else null
        }
        val outcome = commit(gateway)
        assertTrue((outcome as SafCommitOutcome.Failed).error is TransferStorageError.StorageFull)
    }

    // -- Ownership ------------------------------------------------------------

    @Test
    fun `visible final copy is refused unless policy explicitly allows it`() {
        val gateway = RecordingSafGateway()

        val outcome = commit(
            gateway,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = false,
        )

        val failed = outcome as SafCommitOutcome.Failed
        assertEquals(
            TransferStorageError.Unsupported("saf_visible_final_copy"),
            failed.error,
        )
        assertEquals(0, gateway.countOf("create:"))
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `an explicitly allowed visible final copy reports delivery without renaming`() {
        val gateway = RecordingSafGateway()

        val outcome = commit(
            gateway,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            allowVisibleFinalCopy = true,
        )

        assertTrue("verified final copy should commit", outcome is SafCommitOutcome.Committed)
        assertTrue(gateway.calls.any { it.startsWith("create:") && it.endsWith(":movie.mp4") })
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `a temp rename refusal never silently downgrades to visible final copy`() {
        val gateway = RecordingSafGateway(
            renameFailure = TransferStorageError.Unsupported("saf_rename"),
        )

        val outcome = commit(gateway, strategy = SafCommitStrategy.TEMP_THEN_RENAME)

        assertFalse(outcome.isDelivered)
        assertEquals("only the temporary document was created", 1, gateway.countOf("create:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertTrue(gateway.calls.single { it.startsWith("create:") }.endsWith(".morsec-part"))
    }

    @Test
    fun `rename policy rechecks every candidate after a collision appears`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        var lookups = 0
        gateway.throwOn = { operation ->
            if (operation == RecordingSafGateway.OP_FIND_CHILD && ++lookups == 2) {
                gateway.addNamed("$treeUri/document/raced-collision", "movie (1).mp4")
            }
            null
        }

        val outcome = commit(gateway, policy = DuplicatePolicy.RENAME)

        assertTrue(outcome is SafCommitOutcome.Committed)
        val requestedTemporaryName = "movie (2).mp4.p-1.morsec-part"
        assertTrue(
            "the raced candidate must be skipped, not overwritten",
            gateway.calls.any { it.startsWith("create:") && it.endsWith(":$requestedTemporaryName") },
        )
        assertFalse(
            "the candidate that raced into existence must not be selected",
            gateway.calls.any { it.startsWith("create:") && it.endsWith(":movie (1).mp4.p-1.morsec-part") },
        )
        assertTrue("the raced document remains", "$treeUri/document/raced-collision" in gateway.existing)
    }

    @Test
    fun `overwrite stops if the selected target disappears before it is rechecked`() {
        val gateway = RecordingSafGateway()
        val existingUri = "$treeUri/document/seed"
        gateway.addNamed(existingUri, "movie.mp4")
        gateway.throwOn = { operation ->
            if (operation == RecordingSafGateway.OP_CREATE) gateway.existing.remove(existingUri)
            null
        }

        val outcome = commit(gateway, policy = DuplicatePolicy.OVERWRITE)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertEquals("no backup move or deletion is safe after the race", 0, gateway.countOf("rename:$existingUri:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertFalse(outcome.isDelivered)
    }

    @Test
    fun `a commit that stops for reconciliation still closes every handle`() {
        val gateway = RecordingSafGateway(
            renameReturns = RecordingSafGateway.RenameReturn.NEW,
            renameKeepsOriginal = true,
        )
        commit(gateway)
        assertEquals(
            "an ambiguous rename must not leak the verification reader",
            gateway.openedHandles,
            gateway.closedHandles,
        )
    }
}
