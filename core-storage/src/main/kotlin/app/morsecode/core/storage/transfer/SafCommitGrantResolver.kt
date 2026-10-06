package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri
import app.morsecode.core.data.db.SafGrantDao
import kotlinx.coroutines.CancellationException

/** Exact, provider-free grant resolution result used before any document operation. */
internal sealed interface SafCommitGrantResolution {
    data class Available(val grant: SafTreeGrant) : SafCommitGrantResolution
    data object Revoked : SafCommitGrantResolution
    data class Malformed(val code: Code) : SafCommitGrantResolution
    data class Unavailable(val code: Code) : SafCommitGrantResolution

    enum class Code {
        CHECKPOINT_INVALID,
        ROW_ID_INVALID,
        ROW_MISSING,
        ROW_MISMATCH,
        TREE_IDENTITY_INVALID,
        PERMISSION_QUERY_FAILED,
        DATABASE_QUERY_FAILED,
    }
}

internal fun interface SafCommitGrantResolver {
    suspend fun resolve(checkpoint: SafCommitCheckpoint): SafCommitGrantResolution
}

internal data class SafPersistedTreePermission(
    val uri: String,
    val canRead: Boolean,
    val canWrite: Boolean,
)

internal fun interface SafPersistedTreePermissionReader {
    fun read(): List<SafPersistedTreePermission>
}

internal class ContentResolverSafPermissionReader(
    private val contentResolver: ContentResolver,
) : SafPersistedTreePermissionReader {
    override fun read(): List<SafPersistedTreePermission> =
        contentResolver.persistedUriPermissions.map { permission ->
            SafPersistedTreePermission(
                uri = permission.uri.toString(),
                canRead = permission.isReadPermission,
                canWrite = permission.isWritePermission,
            )
        }
}

/**
 * Resolves only the exact grant row and exact persisted tree authority recorded in a checkpoint.
 * This reads Room and ContentResolver's persisted-permission list; it never prompts or queries a
 * document provider and never substitutes another grant.
 */
internal class RoomSafCommitGrantResolver(
    private val grants: SafGrantDao,
    private val permissions: SafPersistedTreePermissionReader,
) : SafCommitGrantResolver {

    override suspend fun resolve(checkpoint: SafCommitCheckpoint): SafCommitGrantResolution {
        SafCommitCheckpointValidator.validate(checkpoint)?.let {
            return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.CHECKPOINT_INVALID)
        }
        val persistedId = checkpoint.approvedTree.grantId.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.ROW_ID_INVALID)
        if (persistedId.toString() != checkpoint.approvedTree.grantId) {
            return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.ROW_ID_INVALID)
        }
        val row = try {
            grants.find(persistedId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SafCommitGrantResolution.Unavailable(SafCommitGrantResolution.Code.DATABASE_QUERY_FAILED)
        } ?: return SafCommitGrantResolution.Unavailable(SafCommitGrantResolution.Code.ROW_MISSING)

        if (row.id != persistedId || row.id.toString() != checkpoint.approvedTree.grantId ||
            row.treeUri != checkpoint.approvedTree.treeUri
        ) {
            return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.ROW_MISMATCH)
        }
        if (!row.readWrite) return SafCommitGrantResolution.Revoked

        val strictUri = runCatching { java.net.URI(row.treeUri) }.getOrNull()
            ?: return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.TREE_IDENTITY_INVALID)
        val treeUri = runCatching { Uri.parse(row.treeUri) }.getOrNull()
            ?: return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.TREE_IDENTITY_INVALID)
        val authority = checkpoint.approvedTree.authority
        val rootDocumentId = SafContainment.treeDocumentIdOf(treeUri)
        if (strictUri.scheme != "content" || strictUri.rawAuthority != authority ||
            strictUri.rawQuery != null || strictUri.rawFragment != null ||
            treeUri.scheme != "content" || treeUri.authority != authority ||
            rootDocumentId == null || rootDocumentId != checkpoint.approvedTree.rootDocumentId
        ) {
            return SafCommitGrantResolution.Malformed(SafCommitGrantResolution.Code.TREE_IDENTITY_INVALID)
        }

        val persistedPermissions = try {
            permissions.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return SafCommitGrantResolution.Revoked
        } catch (_: Exception) {
            return SafCommitGrantResolution.Unavailable(
                SafCommitGrantResolution.Code.PERMISSION_QUERY_FAILED,
            )
        }
        val exactPermission = persistedPermissions.firstOrNull { permission ->
            permission.uri == row.treeUri
        }
        if (exactPermission == null || !exactPermission.canRead || !exactPermission.canWrite) {
            return SafCommitGrantResolution.Revoked
        }

        return SafCommitGrantResolution.Available(
            SafTreeGrant(
                grantId = row.id.toString(),
                treeUri = treeUri,
                rootDocumentId = rootDocumentId,
                authority = authority,
                writable = true,
            ),
        )
    }
}
