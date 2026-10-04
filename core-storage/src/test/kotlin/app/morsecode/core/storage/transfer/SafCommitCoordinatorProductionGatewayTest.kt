package app.morsecode.core.storage.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the coordinator through the registered provider and real gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitCoordinatorProductionGatewayTest {

    private val authority = "app.morsecode.test.commit"
    private val treeUri = Uri.parse("content://$authority/tree/primary%3ADownload")
    private val rootId = "primary:Download"
    private val parentId = "opaque-folder-42"
    private val payload = "production-gateway-commit".toByteArray()

    private lateinit var provider: FakeSafProvider
    private lateinit var context: Context
    private lateinit var gateway: DocumentsContractSafGateway
    private lateinit var grant: SafTreeGrant

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = Robolectric.buildContentProvider(FakeSafProvider::class.java)
            .create(ProviderInfo().apply {
                authority = this@SafCommitCoordinatorProductionGatewayTest.authority
                grantUriPermissions = true
            })
            .get()
        provider.reset(rootId, treeUri)
        provider.addDocument(
            id = parentId,
            name = "folder",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
            flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            parentId = rootId,
        )
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        gateway = DocumentsContractSafGateway(context.contentResolver)
        grant = requireNotNull(gateway.resolveGrant("grant-commit", treeUri.toString(), authority))
    }

    @Test
    fun `production factory commits through DocumentsContract and verifies the renamed identity`() {
        val staging = MemoryStaging(payload)
        val coordinator = SafCommitCoordinatorFactory.create(
            resolver = context.contentResolver,
            staging = staging,
            copyBufferBytes = 7,
        )

        val outcome = coordinator.commit(record(), grant)

        val committed = outcome as? SafCommitOutcome.Committed ?: error("got $outcome")
        assertTrue(coordinator.gateway is DocumentsContractSafGateway)
        assertTrue("nested destination containment must be provider-confirmed", provider.callCount("android:isChildDocument") > 0)
        assertEquals(1, provider.callCount("android:createDocument"))
        assertEquals(1, provider.callCount("android:renameDocument"))
        assertTrue(staging.deleted)

        val final = gateway.query(committed.finalUri) as? SafLookup.Found
            ?: error("final identity did not query as a document")
        assertEquals("movie.mp4", final.document.displayName)
        assertEquals(parentId, final.document.documentId.substringBeforeLast('/'))
        assertEquals(payload.size.toLong(), final.document.sizeBytes)
    }

    @Test
    fun `production gateway permission failure stops before rename and retains staging`() {
        provider.throwSecurityOnOpen = true
        val staging = MemoryStaging(payload)
        val coordinator = SafCommitCoordinatorFactory.create(
            resolver = context.contentResolver,
            staging = staging,
        )

        val outcome = coordinator.commit(record(), grant)

        val pending = outcome as? SafCommitOutcome.ReconciliationRequired
            ?: error("permission revocation must require reconciliation, got $outcome")
        assertTrue(pending.error is TransferStorageError.PermissionRevoked)
        assertEquals(0, provider.callCount("android:renameDocument"))
        assertFalse(staging.deleted)
        assertFalse(outcome.isDelivered)
    }

    private fun record() = SafCommitRecord(
        transferId = TransferId("transfer-prod"),
        partialId = PartialIdentity("partial-prod"),
        treeUri = treeUri.toString(),
        rootDocumentId = rootId,
        parentDocumentId = parentId,
        expectedFinalName = "movie.mp4",
        expectedSizeBytes = payload.size.toLong(),
        expectedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
        strategy = SafCommitStrategy.TEMP_THEN_RENAME,
        duplicatePolicy = DuplicatePolicy.RENAME,
    )

    private class MemoryStaging(private val bytes: ByteArray) : SafStaging {
        var deleted: Boolean = false
            private set

        override fun length(identity: PartialIdentity): Long = bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))

        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }
}
