package app.morsecode.core.storage.transfer

import android.provider.DocumentsContract
import app.morsecode.core.data.db.SafGrantDao
import app.morsecode.core.data.db.SafGrantEntity
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSafCommitGrantResolverTest {
    private val treeUri = DocumentsContract.buildTreeDocumentUri("example.provider", "root")
    private val treeText = treeUri.toString()

    @Test
    fun `available authority is reconstructed only from the exact row and exact live permission`() = runBlocking {
        val resolver = resolver(
            row = SafGrantEntity(id = 7L, treeUri = treeText, displayName = "Downloads"),
            permissions = listOf(SafPersistedTreePermission(treeText, canRead = true, canWrite = true)),
        )

        val result = resolver.resolve(checkpoint(grantId = "7"))

        assertTrue(result is SafCommitGrantResolution.Available)
        val grant = (result as SafCommitGrantResolution.Available).grant
        assertEquals("7", grant.grantId)
        assertEquals(treeText, grant.treeUri.toString())
        assertEquals("example.provider", grant.authority)
        assertEquals("root", grant.rootDocumentId)
        assertTrue(grant.writable)
    }

    @Test
    fun `malformed id row mismatch and broader permission are never substituted`() = runBlocking {
        val leadingZero = resolver(
            row = SafGrantEntity(id = 7L, treeUri = treeText, displayName = "Downloads"),
            permissions = listOf(SafPersistedTreePermission(treeText, true, true)),
        )
        assertTrue(leadingZero.resolve(checkpoint(grantId = "07")) is SafCommitGrantResolution.Malformed)
        assertEquals(0, leadingZero.permissionReader.reads)

        val broaderTree = DocumentsContract.buildTreeDocumentUri("example.provider", "root/child").toString()
        val mismatch = resolver(
            row = SafGrantEntity(id = 7L, treeUri = broaderTree, displayName = "Other"),
            permissions = listOf(SafPersistedTreePermission(broaderTree, true, true)),
        )
        assertTrue(mismatch.resolve(checkpoint(grantId = "7")) is SafCommitGrantResolution.Malformed)
        assertEquals(0, mismatch.permissionReader.reads)

        val exactRowButOnlyBroaderPermission = resolver(
            row = SafGrantEntity(id = 7L, treeUri = treeText, displayName = "Downloads"),
            permissions = listOf(SafPersistedTreePermission(broaderTree, true, true)),
        )
        assertEquals(
            SafCommitGrantResolution.Revoked,
            exactRowButOnlyBroaderPermission.resolve(checkpoint(grantId = "7")),
        )
    }

    @Test
    fun `missing write permission is revoked and missing data is unavailable`() = runBlocking {
        val revoked = resolver(
            row = SafGrantEntity(id = 7L, treeUri = treeText, displayName = "Downloads"),
            permissions = listOf(SafPersistedTreePermission(treeText, canRead = true, canWrite = false)),
        )
        assertEquals(SafCommitGrantResolution.Revoked, revoked.resolve(checkpoint("7")))

        val missingRow = resolver(row = null, permissions = emptyList())
        assertEquals(
            SafCommitGrantResolution.Unavailable(SafCommitGrantResolution.Code.ROW_MISSING),
            missingRow.resolve(checkpoint("7")),
        )
        assertEquals(0, missingRow.permissionReader.reads)

        val unavailablePermissions = resolver(
            row = SafGrantEntity(id = 7L, treeUri = treeText, displayName = "Downloads"),
            permissions = emptyList(),
            permissionFailure = IllegalStateException("sensitive provider text"),
        )
        val unavailable = unavailablePermissions.resolve(checkpoint("7"))
        assertEquals(
            SafCommitGrantResolution.Unavailable(SafCommitGrantResolution.Code.PERMISSION_QUERY_FAILED),
            unavailable,
        )
        assertFalse(unavailable.toString().contains("sensitive provider text"))
    }

    @Test
    fun `database failure never turns into permission or provider access`() = runBlocking {
        val grants = FakeGrantDao(row = null, failRead = true)
        val reader = FakePermissionReader(listOf(SafPersistedTreePermission(treeText, true, true)))
        val resolver = RoomSafCommitGrantResolver(grants, reader)

        assertEquals(
            SafCommitGrantResolution.Unavailable(SafCommitGrantResolution.Code.DATABASE_QUERY_FAILED),
            resolver.resolve(checkpoint("7")),
        )
        assertEquals(0, reader.reads)
    }

    private fun resolver(
        row: SafGrantEntity?,
        permissions: List<SafPersistedTreePermission>,
        permissionFailure: Exception? = null,
    ): TestResolver {
        val reader = FakePermissionReader(permissions, permissionFailure)
        return TestResolver(
            RoomSafCommitGrantResolver(FakeGrantDao(row), reader),
            reader,
        )
    }

    private fun checkpoint(grantId: String): SafCommitCheckpoint {
        val grant = SafTreeGrant(
            grantId = grantId,
            treeUri = treeUri,
            rootDocumentId = "root",
            authority = "example.provider",
            writable = true,
        )
        val record = SafCommitRecord(
            sessionId = SessionId("session-grant-$grantId"),
            transferId = TransferId("transfer-grant-$grantId"),
            partialId = PartialIdentity("partial-grant-$grantId"),
            treeUri = treeText,
            rootDocumentId = "root",
            parentDocumentId = "root",
            expectedFinalName = "file.bin",
            expectedSizeBytes = 0L,
            grantId = grantId,
            duplicatePolicy = DuplicatePolicy.RENAME,
        )
        return SafCommitCheckpoint.fromRecord(record, grant)
    }

    private class FakePermissionReader(
        private val entries: List<SafPersistedTreePermission>,
        private val failure: Exception? = null,
    ) : SafPersistedTreePermissionReader {
        var reads: Int = 0
            private set

        override fun read(): List<SafPersistedTreePermission> {
            reads++
            failure?.let { throw it }
            return entries
        }
    }

    private class FakeGrantDao(
        private val row: SafGrantEntity?,
        private val failRead: Boolean = false,
    ) : SafGrantDao {
        override fun observe(): Flow<List<SafGrantEntity>> = flowOf(listOfNotNull(row))
        override suspend fun find(id: Long): SafGrantEntity? {
            if (failRead) error("injected database failure")
            return row?.takeIf { it.id == id }
        }
        override suspend fun insert(grant: SafGrantEntity): Long = error("not used")
        override suspend fun delete(id: Long) = error("not used")
        override suspend fun deleteByUri(treeUri: String) = error("not used")
        override suspend fun count(): Int = error("not used")
    }

    private data class TestResolver(
        val resolver: SafCommitGrantResolver,
        val permissionReader: FakePermissionReader,
    ) {
        suspend fun resolve(checkpoint: SafCommitCheckpoint): SafCommitGrantResolution =
            resolver.resolve(checkpoint)
    }
}
