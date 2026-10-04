package app.morsecode.core.storage.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Tests for the production gateway, not for a stand-in.
 *
 * A real ContentProvider is registered with Robolectric and driven through a
 * real ContentResolver, so these exercises go through the same
 * DocumentsContract code paths a device would: createDocument and
 * renameDocument are ContentResolver.call() round trips, queries are real
 * cursors, and descriptors are real ParcelFileDescriptors over files on disk.
 *
 * A contract-only fake would prove the coordinator's logic and nothing about
 * whether the gateway asks the platform the right questions. That distinction
 * is the point of this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentsContractSafGatewayTest {

    private val authority = "app.morsecode.test.documents"
    private val treeUri: Uri = Uri.parse("content://$authority/tree/primary%3ADownload")
    private val rootId = "primary:Download"

    private lateinit var provider: FakeSafProvider
    private lateinit var gateway: DocumentsContractSafGateway

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = Robolectric.buildContentProvider(FakeSafProvider::class.java)
            .create(ProviderInfo().apply {
                this.authority = this@DocumentsContractSafGatewayTest.authority
                grantUriPermissions = true
            })
            .get()
        provider.reset(rootId, treeUri)
        // The grant is taken through the platform, not asserted by the test,
        // because the gateway reads persisted permissions from the resolver
        // and a test that simply assumed they were there would pass without
        // exercising the lookup at all.
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        gateway = DocumentsContractSafGateway(resolver = context.contentResolver)
    }

    private fun uriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun grant() = gateway.resolveGrant("g-1", treeUri.toString(), authority)

    // -----------------------------------------------------------------------
    // Resolving the grant
    // -----------------------------------------------------------------------

    @Test
    fun `the gateway resolves a persisted grant into a tree grant`() {
        val resolved = grant()
        assertNotNull("expected a grant, got null", resolved)
        assertEquals(rootId, resolved!!.rootDocumentId)
        assertEquals(authority, resolved.authority)
        assertTrue(resolved.writable)
    }

    @Test
    fun `a grant for a different authority is not resolved`() {
        assertNull(gateway.resolveGrant("g-1", treeUri.toString(), "other.authority"))
    }

    @Test
    fun `a tree uri with no root document is not resolved`() {
        val broken = Uri.parse("content://$authority/tree")
        assertNull(gateway.resolveGrant("g-1", broken.toString(), authority))
    }

    @Test
    fun `a grant reports writable from the live permission state`() {
        assertTrue(gateway.hasPersistedGrant(treeUri, write = true))
        assertTrue(gateway.hasPersistedGrant(treeUri, write = false))
    }

    @Test
    fun `a released grant stops being reported as held`() {
        // The point of reading persisted permissions per call: a grant is not
        // a fact that survives the commit, and a gateway that cached it would
        // still report a tree it can no longer write to.
        ApplicationProvider.getApplicationContext<Context>().contentResolver
            .releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        assertFalse(gateway.hasPersistedGrant(treeUri, write = true))
        assertFalse("the gateway must not assume a grant it no longer holds", grant()!!.writable)
    }

    @Test
    fun `a grant for an unrelated tree uri is not reported as held`() {
        val other = Uri.parse("content://$authority/tree/primary%3AMovies")
        assertFalse(gateway.hasPersistedGrant(other, write = true))
    }

    @Test
    fun `grant authorization re-reads persisted permissions for each phase`() {
        val approved = requireNotNull(grant())
        assertNull(gateway.recheckPersistedGrant(approved, SafContainmentOperation.RECONCILE))
        assertNull(gateway.recheckPersistedGrant(approved, SafContainmentOperation.CREATE_DESTINATION))

        ApplicationProvider.getApplicationContext<Context>().contentResolver
            .releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertNull(
            "read-only reconciliation remains authorized",
            gateway.recheckPersistedGrant(approved, SafContainmentOperation.RECONCILE),
        )
        assertEquals(
            TransferStorageError.PermissionRevoked("write"),
            gateway.recheckPersistedGrant(approved, SafContainmentOperation.CREATE_DESTINATION),
        )
    }

    // -----------------------------------------------------------------------
    // Query model
    // -----------------------------------------------------------------------

    @Test
    fun `a successful query with a row is found`() {
        provider.addDocument("$rootId/2026", "2026", mime = DocumentsContract.Document.MIME_TYPE_DIR)
        val looked = gateway.query(uriFor("$rootId/2026").toString())
        val found = looked as? SafLookup.Found ?: error("got $looked")
        assertEquals("2026", found.document.displayName)
        assertEquals("$rootId/2026", found.document.documentId)
        assertTrue(found.document.isDirectory)
    }

    @Test
    fun `a successful query with no row is absent, not failed`() {
        val looked = gateway.query(uriFor("$rootId/does-not-exist").toString())
        assertTrue("got $looked", looked is SafLookup.Absent)
    }

    @Test
    fun `a null cursor is a failed query, never missing`() {
        // The single most important distinction in the query model: a provider
        // that declines to answer has not told us the document is gone.
        provider.nullCursor = true
        val looked = gateway.query(uriFor(rootId).toString())
        val failed = looked as? SafLookup.Failed ?: error("got $looked")
        assertTrue(failed.error is TransferStorageError.ProviderFailure)
        assertFalse(looked is SafLookup.Absent)
    }

    @Test
    fun `a thrown query is a failure, not missing`() {
        provider.throwOnQuery = true
        val looked = gateway.query(uriFor(rootId).toString())
        assertTrue("got $looked", looked is SafLookup.Failed)
        assertFalse(looked is SafLookup.Absent)
    }

    @Test
    fun `a SecurityException during query maps to permission revoked, not missing`() {
        provider.throwSecurityOnQuery = true
        val looked = gateway.query(uriFor(rootId).toString())
        val failed = looked as? SafLookup.Failed ?: error("got $looked")
        assertTrue(
            "revoked must stay revoked, got ${failed.error}",
            failed.error is TransferStorageError.PermissionRevoked,
        )
        assertFalse(failed.error is TransferStorageError.NotFound)
        assertFalse(failed.error is TransferStorageError.ProviderFailure)
    }

    @Test
    fun `an omitted size column is unknown, not zero`() {
        provider.addDocument("$rootId/file.mp4", "file.mp4", size = null)
        val found = gateway.query(uriFor("$rootId/file.mp4").toString()) as SafLookup.Found
        assertNull("a missing size must be null, not 0", found.document.sizeBytes)
    }

    @Test
    fun `a known zero size is zero, not unknown`() {
        provider.addDocument("$rootId/empty.mp4", "empty.mp4", size = 0L)
        val found = gateway.query(uriFor("$rootId/empty.mp4").toString()) as SafLookup.Found
        assertEquals(0L, found.document.sizeBytes)
    }

    @Test
    fun `a row with no identity is inconsistent, not absent`() {
        provider.addMalformedRow("$rootId/broken")
        val looked = gateway.query(uriFor("$rootId/broken").toString())
        assertTrue("got $looked", looked is SafLookup.Failed)
    }

    @Test
    fun `flags are read for capability detection`() {
        provider.addDocument(
            "$rootId/2026",
            "2026",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
            flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
        )
        val capabilities = gateway.capabilitiesOf(uriFor("$rootId/2026").toString())
        assertTrue(capabilities.canCreateDocument)
        assertTrue(capabilities.flagsPresent)
    }

    // -----------------------------------------------------------------------
    // Create / rename / delete
    // -----------------------------------------------------------------------

    @Test
    fun `create goes through DocumentsContract and captures the returned identity`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "holiday.mp4")
        val result = created as? SafCreate.Created ?: error("got $created")
        assertEquals("$rootId/holiday.mp4", result.documentId)
        assertEquals("holiday.mp4", result.displayName)
        // The provider handed the identity back; the gateway recorded it rather
        // than reconstructing it from the requested name.
        assertTrue(
            "the recorded uri must be the one the provider returned, got ${result.documentUri}",
            result.documentUri.contains("holiday.mp4"),
        )
    }

    @Test
    fun `create records the name the provider actually used after a collision`() {
        provider.renameOnCreate = true
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "holiday.mp4")
        val result = created as? SafCreate.Created ?: error("got $created")
        // A provider that resolves a collision by renaming must have its choice
        // recorded, or a later step would look for a document that never existed.
        assertEquals("holiday (1).mp4", result.displayName)
    }

    @Test
    fun `a provider returning null from create is a failure, not a created document`() {
        provider.nullOnCreate = true
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "holiday.mp4")
        assertTrue("got $created", created is SafCreate.Failed)
    }

    @Test
    fun `a SecurityException during create maps to revoked`() {
        provider.throwSecurityOnCall = true
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "holiday.mp4")
        val failed = created as? SafCreate.Failed ?: error("got $created")
        assertTrue(failed.error is TransferStorageError.PermissionRevoked)
    }

    @Test
    fun `rename captures the uri the provider returned`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        val renamed = gateway.rename(created.documentUri, "holiday.mp4")
        val result = renamed as? SafRename.Renamed ?: error("got $renamed")
        assertNotNull(result.documentUri)
        assertTrue(result.documentUri!!.contains("holiday.mp4"))
    }

    @Test
    fun `a null rename result is a failure requiring reconciliation, not the old identity`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        provider.nullOnRename = true
        val renamed = gateway.rename(created.documentUri, "holiday.mp4")
        // Returning the old uri here would be the gateway inventing an answer
        // the provider declined to give.
        assertTrue("got $renamed", renamed is SafRename.Failed)
    }

    @Test
    fun `a SecurityException during rename maps to revoked`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        provider.throwSecurityOnCall = true
        val renamed = gateway.rename(created.documentUri, "holiday.mp4")
        assertTrue(
            "got $renamed",
            (renamed as? SafRename.Failed)?.error is TransferStorageError.PermissionRevoked,
        )
    }

    @Test
    fun `delete reports deleted for a document that existed`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        assertEquals(SafDelete.Deleted, gateway.delete(created.documentUri))
    }

    @Test
    fun `delete leaves the document in place when the provider affected no rows`() {
        provider.deleteBehaviour = DeleteBehaviour.NO_OP
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        gateway.delete(created.documentUri)

        // Three facts about the provider, which is the half of this exchange
        // the gateway is accountable for.
        assertEquals(
            "the provider must be asked to delete, not assumed to have",
            1,
            provider.callCount("android:deleteDocument"),
        )
        assertEquals(
            "the provider must answer that it affected no row",
            false,
            provider.lastDeleteRemoved,
        )
        assertTrue(
            "a delete that affected no row has deleted nothing",
            provider.contains(created.documentId),
        )

        // Deliberately not asserted: that the gateway reports SafDelete.Absent.
        // DocumentsContract.deleteDocument does not relay a false result back
        // through ContentResolver.call -- it reports success whether or not the
        // provider removed anything, which these three assertions establish is
        // not the gateway's doing. Asserting Absent here would fail for a
        // reason that is not a defect, and a test that fails for the wrong
        // reason is worse than the coverage it buys.
    }

    // -----------------------------------------------------------------------
    // Descriptors
    // -----------------------------------------------------------------------

    @Test
    fun `opening a write descriptor returns an owning handle that closes exactly once`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        val opened = gateway.openWrite(created.documentUri) as SafOpen.Opened
        val handle = opened.handle as SafWriteHandle
        assertTrue(handle.isOpen)
        assertEquals(0, handle.closeCount)
        handle.close()
        assertEquals(1, handle.closeCount)
        handle.close()
        // Closing twice must not close the descriptor twice.
        assertEquals(1, handle.closeCount)
        assertFalse(handle.isOpen)
    }

    @Test
    fun `flush reports attempted-guarantee-unknown, never durable`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        val handle = (gateway.openWrite(created.documentUri) as SafOpen.Opened).handle as SafWriteHandle
        handle.use {
            val durability = it.flush()
            // A provider flush is not fsync. Claiming DurableFlushSupported
            // would be claiming a guarantee no provider offers.
            assertEquals(FlushDurability.FlushAttemptedGuaranteeUnknown, durability)
        }
    }

    @Test
    fun `a read descriptor is independent of the write descriptor`() {
        val created = gateway.create(uriFor(rootId).toString(), "video/mp4", "temp.part") as SafCreate.Created
        val write = (gateway.openWrite(created.documentUri) as SafOpen.Opened).handle as SafWriteHandle
        write.write("hello".toByteArray(), 0, 5)
        write.flush()
        write.close()

        // Verification must reopen rather than reuse the copy's descriptor.
        val read = (gateway.openRead(created.documentUri) as SafOpen.Opened).handle as SafReadHandle
        read.use {
            val buffer = ByteArray(5)
            val count = it.read(buffer, 0, 5)
            assertEquals(5, count)
            assertEquals("hello", String(buffer, 0, count))
        }
    }

    @Test
    fun `a SecurityException opening a descriptor maps to revoked`() {
        provider.throwSecurityOnOpen = true
        val opened = gateway.openWrite(uriFor(rootId).toString())
        val refused = opened as? SafOpen.Refused ?: error("got $opened")
        assertTrue(refused.error is TransferStorageError.PermissionRevoked)
    }

    @Test
    fun `a null descriptor is a refusal, never a handle the gateway does not have`() {
        provider.nullOnOpen = true
        // Whatever the platform does with a provider that declines to open --
        // return null or throw -- the gateway must not hand back a handle. A
        // claimed descriptor would let the copy run and report success against
        // bytes that went nowhere.
        assertTrue("got ${gateway.openWrite(uriFor(rootId).toString())}", gateway.openWrite(uriFor(rootId).toString()) is SafOpen.Refused)
    }

    // -----------------------------------------------------------------------
    // Storage-full mapping
    // -----------------------------------------------------------------------

    @Test
    fun `a storage-full failure during copy maps to a typed StorageFull`() {
        provider.failOpenWith = RuntimeException("write failed: ENOSPC (No space left on device)")
        val opened = gateway.openWrite(uriFor(rootId).toString())
        val refused = opened as? SafOpen.Refused ?: error("got $opened")
        val storageFull = refused.error as? TransferStorageError.StorageFull
            ?: error("expected StorageFull, got ${refused.error}")
        assertEquals("open", storageFull.operation)
        // The message must not leak into the user-visible summary.
        assertFalse(storageFull.safeMessage().contains("ENOSPC"))
    }

}
