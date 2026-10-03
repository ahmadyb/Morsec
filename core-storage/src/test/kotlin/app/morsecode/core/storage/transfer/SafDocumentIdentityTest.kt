package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.transfer.identity.TransferId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Identity: whether an operation may touch a specific document this
 * application created.
 *
 * Containment says a place is inside the grant. It does not say a document is
 * ours. These tests prove the second question is answered from the recorded
 * identity, never from a name and never from a fresh search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafDocumentIdentityTest {

    private val authority = "com.android.externalstorage.documents"
    private val treeUri = Uri.parse("content://$authority/tree/primary%3ADownload")

    private val grant = SafTreeGrant(
        grantId = "g-1",
        treeUri = treeUri,
        rootDocumentId = "primary:Download",
        authority = authority,
        writable = true,
    )

    private val transferId = TransferId("t-1")
    private val commitId = PartialIdentity.of(transferId, DestinationStrategy.SAF_STAGED)

    private val tempUri =
        "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2Fholiday.mp4.part"
    private val tempId = "primary:Download/holiday.mp4.part"

    private val identity = InternallyCreatedDocument(
        grantId = "g-1",
        treeUri = treeUri.toString(),
        authority = authority,
        parentDocumentId = "primary:Download",
        documentUri = tempUri,
        documentId = tempId,
        transferId = transferId,
        commitId = commitId,
    )

    private val context = SafOperationContext(
        grant = grant,
        transferId = transferId,
        commitId = commitId,
    )

    private fun allowed(
        operation: SafContainmentOperation,
        presented: String? = tempUri,
        ident: InternallyCreatedDocument = identity,
        ctx: SafOperationContext = context,
        commitStateAllows: Boolean = true,
    ) = SafDocumentIdentityPolicy.decide(ident, ctx, operation, presented, commitStateAllows)

    // -----------------------------------------------------------------------
    // The exact stored identity authorises the workflow
    // -----------------------------------------------------------------------

    @Test
    fun `the exact stored identity permits opening for write`() {
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.OPEN_WRITE))
    }

    @Test
    fun `the exact stored identity permits verification`() {
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.VERIFY))
    }

    @Test
    fun `the exact stored identity permits a rename attempt`() {
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.RENAME))
    }

    @Test
    fun `the exact stored identity permits reconciliation`() {
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.RECONCILE))
    }

    @Test
    fun `the exact stored temporary identity permits cleanup`() {
        assertEquals(
            SafContainmentDecision.ALLOWED,
            allowed(SafContainmentOperation.DELETE_TEMPORARY),
        )
    }

    @Test
    fun `no presented uri means the stored identity itself, which permits non-delete work`() {
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.OPEN_WRITE, null))
        assertEquals(SafContainmentDecision.ALLOWED, allowed(SafContainmentOperation.VERIFY, null))
    }

    // -----------------------------------------------------------------------
    // Everything that must be refused
    // -----------------------------------------------------------------------

    @Test
    fun `an arbitrary existing uri is forbidden`() {
        val other = "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2Fother.mp4"
        assertEquals(SafContainmentDecision.REJECTED, allowed(SafContainmentOperation.OPEN_WRITE, other))
        assertEquals(SafContainmentDecision.REJECTED, allowed(SafContainmentOperation.DELETE_TEMPORARY, other))
    }

    @Test
    fun `a same-name sibling document is forbidden`() {
        // Identical display name, different document identity. A search by name
        // would find this one; the stored identity must not accept it.
        val sameName = "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2Fholiday.mp4"
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.DELETE_TEMPORARY, sameName),
        )
    }

    @Test
    fun `a different authority is forbidden`() {
        val foreign = identity.copy(
            authority = "other.provider",
            documentUri = tempUri.replace(authority, "other.provider"),
        )
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.OPEN_WRITE, foreign.documentUri, foreign),
        )
    }

    @Test
    fun `a different grant is forbidden`() {
        val otherGrant = grant.copy(grantId = "g-2")
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(
                SafContainmentOperation.OPEN_WRITE,
                ctx = context.copy(grant = otherGrant),
            ),
        )
    }

    @Test
    fun `a different tree under the same grant id is forbidden`() {
        val otherTree = grant.copy(
            treeUri = Uri.parse("content://$authority/tree/primary%3AMovies"),
            rootDocumentId = "primary:Movies",
        )
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.OPEN_WRITE, ctx = context.copy(grant = otherTree)),
        )
    }

    @Test
    fun `a stale document id is forbidden`() {
        val stale = identity.copy(documentId = "$tempId.old")
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.OPEN_WRITE, ident = stale),
        )
    }

    @Test
    fun `a wrong transfer id is forbidden`() {
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(
                SafContainmentOperation.VERIFY,
                ctx = context.copy(transferId = TransferId("t-2")),
            ),
        )
    }

    @Test
    fun `a wrong commit id is forbidden`() {
        val other = PartialIdentity.of(TransferId("t-9"), DestinationStrategy.SAF_STAGED)
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.VERIFY, ctx = context.copy(commitId = other)),
        )
    }

    @Test
    fun `a revoked grant authorises nothing`() {
        assertEquals(
            SafContainmentDecision.FORBIDDEN_NO_WRITE,
            allowed(SafContainmentOperation.OPEN_WRITE, ctx = context.copy(grant = grant.copy(writable = false))),
        )
        assertEquals(
            SafContainmentDecision.FORBIDDEN_NO_WRITE,
            allowed(SafContainmentOperation.DELETE_TEMPORARY, ctx = context.copy(grant = grant.copy(writable = false))),
        )
    }

    @Test
    fun `a commit state that does not authorise the operation is rejected`() {
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.DELETE_TEMPORARY, commitStateAllows = false),
        )
    }

    @Test
    fun `deletion requires the identity to be named explicitly`() {
        // Containment, or merely holding the record, is never enough to delete.
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.DELETE_TEMPORARY, presented = null),
        )
    }

    @Test
    fun `a same-name replacement cannot be deleted using a stale identity`() {
        // The provider recreated the document after a rename, so the old id is
        // gone and a new document sits under the same name. The stale record
        // must not authorise removing it.
        val replacementUri = tempUri.replace("%2Fholiday.mp4.part", "%2Fholiday.mp4")
        val staleIdentity = identity.copy(
            documentUri = "content://$authority/tree/primary%3ADownload/document/gone-id",
            documentId = "gone-id",
        )
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.DELETE_TEMPORARY, replacementUri, staleIdentity),
        )
    }

    // -----------------------------------------------------------------------
    // Rename: the provider may return a different identity
    // -----------------------------------------------------------------------

    private val newUri =
        "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2Fholiday.mp4"
    private val newId = "primary:Download/holiday.mp4"

    @Test
    fun `a returned new uri becomes the authoritative identity`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = newUri,
            returnedDocumentId = newId,
            originalStillResolves = false,
            returnedResolves = true,
        )
        assertEquals(SafRenameReconciliation.RESOLVED_TO_RETURNED, resolved.reconciliation)
        assertEquals(newUri, resolved.authoritativeDocumentUri)
        assertFalse(resolved.requiresReconciliation)
    }

    @Test
    fun `the old uri cannot be used blindly after a rename returns a new one`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = newUri,
            returnedDocumentId = newId,
            originalStillResolves = false,
            returnedResolves = true,
        )
        val updated = SafDocumentIdentityPolicy.afterRename(identity, resolved)!!
        assertEquals(newUri, updated.documentUri)
        assertEquals(newId, updated.documentId)

        // The old identity no longer matches the stored record, so presenting
        // it is refused rather than silently accepted.
        assertEquals(
            SafContainmentDecision.REJECTED,
            allowed(SafContainmentOperation.OPEN_WRITE, tempUri, updated),
        )
        assertEquals(
            SafContainmentDecision.ALLOWED,
            allowed(SafContainmentOperation.OPEN_WRITE, newUri, updated),
        )
    }

    @Test
    fun `a null rename result requires reconciliation`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = null,
            returnedDocumentId = null,
            originalStillResolves = true,
            returnedResolves = false,
        )
        assertEquals(SafRenameReconciliation.NULL_RETURN, resolved.reconciliation)
        assertNull(resolved.authoritativeDocumentUri)
        assertTrue(resolved.requiresReconciliation)
        // Both known identities are preserved for the next pass.
        assertEquals(listOf(tempUri), resolved.knownUris)

        // Nothing may be recorded as authoritative while unresolved.
        assertNull(SafDocumentIdentityPolicy.afterRename(identity, resolved))
    }

    @Test
    fun `both uris resolving requires reconciliation`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = newUri,
            returnedDocumentId = newId,
            originalStillResolves = true,
            returnedResolves = true,
        )
        assertEquals(SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE, resolved.reconciliation)
        assertNull(resolved.authoritativeDocumentUri)
        assertEquals(listOf(tempUri, newUri), resolved.knownUris)
        assertNull(SafDocumentIdentityPolicy.afterRename(identity, resolved))
    }

    @Test
    fun `a rename returning the same identity stays on that identity`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = tempUri,
            returnedDocumentId = tempId,
            originalStillResolves = true,
            returnedResolves = true,
        )
        assertEquals(SafRenameReconciliation.RESOLVED_TO_ORIGINAL, resolved.reconciliation)
        assertEquals(tempUri, resolved.authoritativeDocumentUri)
        assertFalse(resolved.requiresReconciliation)
    }

    @Test
    fun `a returned uri that does not resolve while the original does needs reconciliation`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = newUri,
            returnedDocumentId = newId,
            originalStillResolves = true,
            returnedResolves = false,
        )
        assertEquals(SafRenameReconciliation.RETURNED_UNRESOLVED, resolved.reconciliation)
        assertNull(resolved.authoritativeDocumentUri)
    }

    @Test
    fun `neither uri resolving needs reconciliation`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = newUri,
            returnedDocumentId = newId,
            originalStillResolves = false,
            returnedResolves = false,
        )
        assertEquals(SafRenameReconciliation.NEITHER_RESOLVES, resolved.reconciliation)
        assertNull(resolved.authoritativeDocumentUri)
    }

    @Test
    fun `an unresolved rename reports the rename was requested`() {
        val resolved = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = tempUri,
            beforeDocumentId = tempId,
            returnedDocumentUri = null,
            returnedDocumentId = null,
            originalStillResolves = true,
            returnedResolves = false,
        )
        assertTrue(resolved.renameRequested)
        assertNotNull(resolved.beforeDocumentUri)
    }
}
