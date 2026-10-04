package app.morsecode.core.storage.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Deletion settled by observation.
 *
 * These tests exist because a delete request is not proof of absence. The
 * previous round measured this: a provider that kept the document and answered
 * false was still reported by DocumentsContract.deleteDocument as a success.
 * Any cleanup that treated that boolean as final would mark a temporary gone
 * while it is still sitting under a name the user can see.
 *
 * So each case here asks the same question the production rule asks -- what
 * does a follow-up query on the exact stored identity observe? -- and the
 * delete boolean is only ever checked as a diagnostic, never as the answer.
 *
 * The provider is a real ContentProvider driven through a real ContentResolver,
 * so the delete is a genuine DocumentsContract.call() round trip and the
 * follow-up is a genuine query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafDeletionReconciliationTest {

    private val authority = "app.morsecode.test.deletion"
    private val treeUri: Uri = Uri.parse("content://$authority/tree/primary%3ADownload")
    private val rootId = "primary:Download"

    private lateinit var provider: FakeSafProvider
    private lateinit var gateway: DocumentsContractSafGateway

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = Robolectric.buildContentProvider(FakeSafProvider::class.java)
            .create(ProviderInfo().apply {
                this.authority = this@SafDeletionReconciliationTest.authority
                grantUriPermissions = true
            })
            .get()
        provider.reset(rootId, treeUri)
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        gateway = DocumentsContractSafGateway(resolver = context.contentResolver)
    }

    private fun uriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun created(name: String = "temp.part"): SafCreate.Created =
        gateway.create(uriFor(rootId).toString(), "video/mp4", name) as SafCreate.Created

    private fun delete(target: SafCreate.Created): SafDeletion =
        gateway.deleteAndReconcile(target.documentUri, target.documentId)

    // -----------------------------------------------------------------------
    // What the follow-up query observes
    // -----------------------------------------------------------------------

    @Test
    fun `a delete the provider performs is confirmed absent by the follow-up`() {
        val outcome = delete(created())

        assertTrue("got $outcome", outcome is SafDeletion.ConfirmedAbsent)
        assertTrue("absence proved means cleanup is done", outcome.cleanupComplete)
    }

    @Test
    fun `a provider reporting success while keeping the document leaves it still present`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.KEEPS_BUT_REPORTS_TRUE

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.StillPresent)
        // The provider claimed success and the document is still there. The
        // observation wins, and cleanup stays open.
        assertEquals(
            "the provider must have claimed success",
            true,
            provider.lastDeleteRemoved,
        )
        assertTrue("the document it claimed to delete is still there", provider.contains(target.documentId))
        assertFalse("cleanup must not be closed while the document resolves", outcome.cleanupComplete)
    }

    @Test
    fun `a provider reporting failure while deleting is still confirmed absent`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.REMOVES_BUT_REPORTS_FALSE

        val outcome = delete(target)

        // The case that proves the boolean is not the answer. The provider
        // reported failure, yet the document is gone, so absence is confirmed
        // by observation alone and the request's own return value is carried
        // as a diagnostic that nothing reads.
        //
        // That return value is not asserted here on purpose. This run
        // established that DocumentsContract.deleteDocument does not relay the
        // provider's answer -- it reported success while the provider reported
        // failure -- which is the whole reason absence is proved by query. A
        // test that pinned its value would be pinning the platform's
        // unreliability rather than the gateway's behaviour.
        assertEquals(
            "the provider must have reported failure",
            false,
            provider.lastDeleteRemoved,
        )
        assertTrue("got $outcome", outcome is SafDeletion.ConfirmedAbsent)
        assertTrue(outcome.cleanupComplete)
    }

    // -----------------------------------------------------------------------
    // When the follow-up cannot answer
    // -----------------------------------------------------------------------

    @Test
    fun `a reported success with an unknown follow-up query stays reconciliation-required`() {
        val target = created()
        provider.nullCursor = true

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.QueryUnknown)
        assertEquals(
            "the delete claimed success; the unknown query is what decides",
            true,
            (outcome as SafDeletion.QueryUnknown).deleteReported,
        )
        assertFalse("unknown is not absence", outcome.cleanupComplete)
    }

    @Test
    fun `a null follow-up cursor leaves the deletion unknown`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.NO_OP
        provider.nullCursor = true

        // A provider that declines to answer has not said the document is gone,
        // so this is unknown rather than absent.
        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.QueryUnknown)
    }

    @Test
    fun `a throwing follow-up query leaves the deletion unknown`() {
        val target = created()
        provider.throwOnQuery = true

        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.QueryUnknown)
    }

    // -----------------------------------------------------------------------
    // When the request itself fails
    // -----------------------------------------------------------------------

    @Test
    fun `a provider that throws during delete fails the request`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.THROWS

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.DeleteRequestFailed)
        assertFalse("a failed request is not a completed cleanup", outcome.cleanupComplete)
    }

    @Test
    fun `a revoked grant during delete is reported as revoked`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.THROWS_SECURITY

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.PermissionRevoked)
        assertFalse("revoked is not absence", outcome.cleanupComplete)
    }

    @Test
    fun `a revoked grant during the follow-up query is reported as revoked`() {
        val target = created()
        // The delete goes through; it is the proof of absence that is lost.
        provider.throwSecurityOnQuery = true

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.PermissionRevoked)
        assertFalse(outcome.cleanupComplete)
    }

    // -----------------------------------------------------------------------
    // When the identity answers differently
    // -----------------------------------------------------------------------

    @Test
    fun `a uri resolving to a replacement identity is a mismatch, not an absence`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.NO_OP
        provider.queryDocumentIdOverride = "$rootId/a-different-document"

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.IdentityMismatch)
        val mismatch = outcome as SafDeletion.IdentityMismatch
        assertEquals(target.documentId, mismatch.expectedDocumentId)
        assertEquals("$rootId/a-different-document", mismatch.observedDocumentId)
        // Nothing is deleted and nothing is assumed: this is not our document.
        assertFalse(outcome.cleanupComplete)
    }

    @Test
    fun `a same-name replacement is a mismatch rather than a successful delete`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.NO_OP
        // The name survives; the identity behind it does not match.
        provider.queryDocumentIdOverride = "$rootId/temp.part-replacement"

        val outcome = delete(target)

        assertTrue("got $outcome", outcome is SafDeletion.IdentityMismatch)
        val mismatch = outcome as SafDeletion.IdentityMismatch
        assertEquals(
            "the name is the same, which is exactly why a name must not decide",
            "temp.part",
            mismatch.observedDisplayName,
        )
        assertEquals("$rootId/temp.part-replacement", mismatch.observedDocumentId)
    }

    // -----------------------------------------------------------------------
    // Repeating the cleanup
    // -----------------------------------------------------------------------

    @Test
    fun `repeating a delete of an already absent identity stays confirmed absent`() {
        val target = created()

        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.ConfirmedAbsent)
        // The second call finds nothing to remove and nothing to prove, which
        // is the same observation and must give the same answer.
        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.ConfirmedAbsent)
    }

    @Test
    fun `repeating cleanup of a document that survives stays still present`() {
        val target = created()
        provider.deleteBehaviour = DeleteBehaviour.KEEPS_BUT_REPORTS_TRUE

        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.StillPresent)
        // Idempotent in the direction that matters: a cleanup that cannot
        // finish keeps saying it has not finished rather than drifting towards
        // completion.
        assertTrue("got ${delete(target)}", delete(target) is SafDeletion.StillPresent)
        assertTrue(provider.contains(target.documentId))
    }

    // -----------------------------------------------------------------------
    // The grant is checked before anything is touched
    // -----------------------------------------------------------------------

    @Test
    fun `a delete attempted after the grant is released is revoked, not absent`() {
        val target = created()
        ApplicationProvider.getApplicationContext<Context>().contentResolver
            .releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

        val grant = gateway.resolveGrant("g-1", treeUri.toString(), authority)
        val outcome = gateway.deleteAndReconcile(target.documentUri, target.documentId, grant)

        assertTrue("got $outcome", outcome is SafDeletion.PermissionRevoked)
        // Nothing was touched: the provider was never asked to delete.
        assertEquals(0, provider.callCount("android:deleteDocument"))
    }

    @Test
    fun `a stored id that does not match the requested uri is rejected before delete`() {
        val target = created()
        val differentUri = uriFor("$rootId/a-different-document").toString()

        val outcome = gateway.deleteAndReconcile(differentUri, target.documentId)

        assertTrue("got $outcome", outcome is SafDeletion.IdentityMismatch)
        assertEquals(0, provider.callCount("android:deleteDocument"))
    }

    @Test
    fun `a delete whose uri belongs to another authority is a mismatch, not a delete`() {
        val target = created()
        val grant = gateway.resolveGrant("g-1", treeUri.toString(), authority)!!
        val foreign = "content://other.authority/tree/primary%3ADocument/document/other"

        val outcome = gateway.deleteAndReconcile(foreign, target.documentId, grant)

        assertTrue("got $outcome", outcome is SafDeletion.IdentityMismatch)
        assertEquals(0, provider.callCount("android:deleteDocument"))
    }
}
