package app.morsecode.core.storage.transfer

import androidx.core.net.toUri
import app.morsecode.core.data.db.SafGrantDao
import kotlinx.coroutines.CancellationException

/** Exact persisted-grant resolution; no picker or broader-scope fallback exists. */
public fun interface PersistedSafGrantResolver {
    public suspend fun resolve(checkpoint: SafCommitCheckpoint): PersistedSafGrantResolution
}

public sealed interface PersistedSafGrantResolution {
    public class Available(public val grant: SafTreeGrant) : PersistedSafGrantResolution {
        override fun toString(): String = "PersistedSafGrantResolution.Available([grant redacted])"
    }

    public data object Revoked : PersistedSafGrantResolution
    public data object InsufficientScope : PersistedSafGrantResolution
    public data object Malformed : PersistedSafGrantResolution
    public data object Mismatched : PersistedSafGrantResolution
}

/**
 * Resolves exactly one Room grant row and exactly the Android persisted tree
 * permission named by a validated checkpoint. Production grant ids are the
 * decimal `SafGrantEntity.id` from schema v2; no new grant table or schema is
 * introduced.
 */
internal class RoomPersistedSafGrantResolver(
    private val grantDao: SafGrantDao,
    private val gateway: DocumentsContractSafGateway,
) : PersistedSafGrantResolver {

    override suspend fun resolve(checkpoint: SafCommitCheckpoint): PersistedSafGrantResolution {
        val approved = checkpoint.approvedTree
        val grantRowId = approved.grantId.toLongOrNull()?.takeIf {
            it > 0L && it.toString() == approved.grantId
        } ?: return PersistedSafGrantResolution.Malformed
        val uri = try {
            approved.treeUri.toUri()
        } catch (_: Exception) {
            return PersistedSafGrantResolution.Malformed
        }
        val strictUri = runCatching { java.net.URI(approved.treeUri) }.getOrNull()
            ?: return PersistedSafGrantResolution.Malformed
        val rootDocumentId = SafContainment.treeDocumentIdOf(uri)
        if (strictUri.scheme != "content" ||
            strictUri.rawAuthority != approved.authority ||
            strictUri.rawQuery != null || strictUri.rawFragment != null ||
            uri.scheme != "content" ||
            uri.authority != approved.authority ||
            rootDocumentId == null ||
            rootDocumentId != approved.rootDocumentId
        ) {
            return PersistedSafGrantResolution.Malformed
        }

        val exact = try {
            grantDao.findExact(grantRowId, approved.treeUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (exact == null) {
            val rowWithId = grantDao.findById(grantRowId)
            val rowWithTree = grantDao.findByTreeUri(approved.treeUri)
            if (rowWithId != null || rowWithTree != null) return PersistedSafGrantResolution.Mismatched
            return if (gateway.hasPersistedGrant(uri, write = false)) {
                // An OS permission without its exact Room authority row is not
                // a substitute and cannot be adopted during restoration.
                PersistedSafGrantResolution.Mismatched
            } else {
                PersistedSafGrantResolution.Revoked
            }
        }
        if (exact.id != grantRowId || exact.treeUri != approved.treeUri) {
            return PersistedSafGrantResolution.Mismatched
        }
        if (!exact.readWrite) return PersistedSafGrantResolution.InsufficientScope
        if (!gateway.hasPersistedGrant(uri, write = false)) return PersistedSafGrantResolution.Revoked
        if (!gateway.hasPersistedGrant(uri, write = true)) return PersistedSafGrantResolution.InsufficientScope

        val grant = gateway.resolveGrant(
            grantId = approved.grantId,
            treeUri = approved.treeUri,
            authority = approved.authority,
        ) ?: return when {
            !gateway.hasPersistedGrant(uri, write = false) -> PersistedSafGrantResolution.Revoked
            !gateway.hasPersistedGrant(uri, write = true) -> PersistedSafGrantResolution.InsufficientScope
            else -> PersistedSafGrantResolution.Malformed
        }
        if (grant.grantId != approved.grantId ||
            grant.treeUri.toString() != approved.treeUri ||
            grant.authority != approved.authority ||
            grant.rootDocumentId != approved.rootDocumentId
        ) {
            return PersistedSafGrantResolution.Mismatched
        }
        if (!grant.writable) {
            return if (gateway.hasPersistedGrant(uri, write = false)) {
                PersistedSafGrantResolution.InsufficientScope
            } else {
                PersistedSafGrantResolution.Revoked
            }
        }
        return PersistedSafGrantResolution.Available(grant)
    }
}
