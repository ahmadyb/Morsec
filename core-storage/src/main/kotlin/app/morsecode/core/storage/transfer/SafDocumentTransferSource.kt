package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.saf.PathResolution
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.core.transfer.identity.RelativeTransferPath

/*
 * A source backed by a document inside a SAF tree the user granted.
 *
 * The rule this class exists to enforce: being a `content://` Uri proves nothing.
 * A caller can hand us any Uri it likes — from an intent, from a database row
 * written by an older version, from a peer — and nothing about the scheme says it
 * is inside a tree we were given access to. So before any descriptor is opened the
 * document id is resolved against the persisted grants with SafPaths, and a Uri
 * that is malformed or that lands outside every granted tree is refused.
 *
 * The grant is re-checked on fingerprint and on every open, not once at
 * construction. A grant can be revoked in Settings while this object is alive, and
 * the failure we must not produce is a transfer that silently reads nothing.
 *
 * SAF providers vary. Some hand back seekable descriptors, some do not, and the
 * only way to know is to try — which is what the capability probe is for. Writing
 * separately, SAF does *not* promise a hidden or atomic document, so nothing here
 * claims one; that concern belongs to the destination side.
 */

/**
 * Whether a read grant for a tree is currently held.
 *
 * A seam rather than a direct `persistedUriPermissions` read, because the answer
 * has to be simulatable: the interesting case is a grant that was held a
 * moment ago and is not held now, and a test has to be able to produce that
 * without revoking a real platform permission. The default consults the
 * platform, which remains the fact this decision is made from.
 */
public fun interface SafGrantChecker {
    /** Whether a read grant for [treeUri] is held right now. */
    public fun isReadGranted(treeUri: Uri): Boolean

    public companion object {
        /** The production check: asks the platform what we still hold. */
        public fun fromResolver(resolver: ContentResolver): SafGrantChecker =
            object : SafGrantChecker {
                override fun isReadGranted(treeUri: Uri): Boolean = try {
                    resolver.persistedUriPermissions.any { permission ->
                        permission.uri == treeUri && permission.isReadPermission
                    }
                } catch (e: Exception) {
                    false
                }
            }
    }
}

/** The result of accepting — or refusing — a document Uri. */
public sealed interface SafSourceResult {
    /** The Uri is inside a granted tree and a source was built for it. */
    public data class Ready(public val source: TransferSource) : SafSourceResult

    /** The Uri was refused before anything was opened. */
    public data class Refused(public val error: TransferStorageError) : SafSourceResult
}

/** One SAF document row, as read through the resolver. */
internal data class SafDocumentRow(
    val documentId: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val modifiedEpochMillis: Long?,
)

/**
 * A SAF-backed source for one document inside one granted tree.
 *
 * [treeUri] is retained because the grant has to be re-validated on every use.
 */
public class SafDocumentTransferSource internal constructor(
    override val key: TransferSourceKey,
    override val uri: Uri,
    override val displayName: String,
    override val relativePath: RelativeTransferPath,
    override val mimeType: String,
    private val documentId: String?,
    private val treeUri: Uri,
    private val reportedSizeBytes: Long,
    private val reportedModifiedEpochMillis: Long?,
    private val capability: ProbeResult,
    private val resolver: ContentResolver,
    private val probe: SeekabilityProbe,
    private val grantChecker: SafGrantChecker,
) : TransferSource {

    override val isSeekable: Boolean get() = capability.seekability.canSeek

    override val sizeBytes: Long get() = reportedSizeBytes

    override val lastModifiedEpochMillis: Long? get() = reportedModifiedEpochMillis

    override fun fingerprint(): SourceFingerprintResult {
        if (!grantHeld()) {
            return SourceFingerprintResult.Unavailable(
                TransferStorageError.PermissionRevoked("read"),
            )
        }
        return try {
            val row = queryRow()
                ?: return SourceFingerprintResult.Unavailable(
                    TransferStorageError.NotFound("source"),
                )
            SourceFingerprintResult.Available(
                SourceFingerprint(
                    sizeBytes = row.sizeBytes,
                    lastModifiedEpochMillis = row.modifiedEpochMillis,
                    contentId = contentId,
                ),
            )
        } catch (e: SecurityException) {
            SourceFingerprintResult.Unavailable(TransferStorageError.PermissionRevoked("read"))
        } catch (e: Exception) {
            SourceFingerprintResult.Unavailable(
                TransferStorageError.ProviderFailure("saf", e.message),
            )
        }
    }

    override fun openAt(offset: Long): SourceOpenResult {
        if (!grantHeld()) {
            return SourceOpenResult.Failed(TransferStorageError.PermissionRevoked("read"))
        }
        return when (val opened = ProviderSourceOpener.open(resolver, uri, offset, probe)) {
            is ProviderOpenResult.Opened ->
                SourceOpenResult.Opened(opened.handle, opened.offset)

            is ProviderOpenResult.Failed ->
                SourceOpenResult.Failed(opened.error)
        }
    }

    /**
     * Whether the grant for this tree is still held.
     *
     * Checked against the permissions the platform says we hold, not against the
     * row in our database — the row is a record of what was granted, and the
     * platform is the fact.
     */
    private fun grantHeld(): Boolean = grantChecker.isReadGranted(treeUri)

    /** The document id is the identity: it is a provider-issued handle, not a path. */
    private val contentId: String? get() = documentId?.let { "doc:$it" }

    private fun queryRow(): SafDocumentRow? {
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return SafDocumentRow(
                documentId = it.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                displayName = it.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: it.stringOrNull(OpenableColumns.DISPLAY_NAME),
                sizeBytes = it.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
                    ?: it.longOrNull(OpenableColumns.SIZE),
                mimeType = it.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE),
                modifiedEpochMillis = it.longOrNull(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
            )
        }
    }

    internal companion object {
        internal val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Builds SAF sources, refusing anything outside the granted trees. */
public object SafDocumentTransferSources {

    public fun create(
        resolver: ContentResolver,
        grants: List<SafGrant>,
        documentUri: Uri,
        relativePath: RelativeTransferPath,
        displayName: String? = null,
        mimeType: String? = null,
        probe: SeekabilityProbe = ParcelDescriptorSeekabilityProbe,
        grantChecker: SafGrantChecker = SafGrantChecker.fromResolver(resolver),
    ): SafSourceResult {
        val documentId = SafPaths.documentIdOf(documentUri.toString())
            ?: return SafSourceResult.Refused(
                TransferStorageError.Unsupported("saf_document_uri"),
            )

        val grant = grants.firstOrNull { candidate ->
            when (SafPaths.resolve(documentId, candidate.treeUri)) {
                is PathResolution.Inside -> true
                else -> false
            }
        } ?: return SafSourceResult.Refused(
            TransferStorageError.Unsupported("saf_outside_tree"),
        )

        val treeUri = grant.treeUri.toUri()
        if (!grantChecker.isReadGranted(treeUri)) {
            return SafSourceResult.Refused(TransferStorageError.PermissionRevoked("read"))
        }

        val row = try {
            queryRow(resolver, documentUri)
        } catch (e: SecurityException) {
            return SafSourceResult.Refused(TransferStorageError.PermissionRevoked("read"))
        } catch (e: Exception) {
            null
        }
        val capability = probeCapability(resolver, documentUri, probe)
        val name = displayName
            ?: row?.displayName
            ?: relativePath.lastSegment.takeIf { it.isNotBlank() }
            ?: "file"

        return SafSourceResult.Ready(
            SafDocumentTransferSource(
                key = TransferSourceKey("saf:$documentId"),
                uri = documentUri,
                displayName = name,
                relativePath = relativePath,
                mimeType = mimeType ?: row?.mimeType ?: guessMimeType(name),
                documentId = row?.documentId ?: documentId,
                treeUri = treeUri,
                // -1 means "the provider would not say", not "empty".
                reportedSizeBytes = row?.sizeBytes ?: -1L,
                reportedModifiedEpochMillis = row?.modifiedEpochMillis,
                capability = capability,
                resolver = resolver,
                probe = probe,
                grantChecker = grantChecker,
            ),
        )
    }

    /** Confirms the descendant is inside the tree before it is used for anything else. */
    public fun isInsideGrantedTree(
        grants: List<SafGrant>,
        documentUri: Uri,
    ): Boolean {
        val documentId = SafPaths.documentIdOf(documentUri.toString()) ?: return false
        return grants.any { candidate ->
            SafPaths.resolve(documentId, candidate.treeUri) is PathResolution.Inside
        }
    }

    private fun queryRow(resolver: ContentResolver, uri: Uri): SafDocumentRow? {
        val cursor = resolver.query(uri, SafDocumentTransferSource.PROJECTION, null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return SafDocumentRow(
                documentId = it.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                displayName = it.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: it.stringOrNull(OpenableColumns.DISPLAY_NAME),
                sizeBytes = it.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
                    ?: it.longOrNull(OpenableColumns.SIZE),
                mimeType = it.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE),
                modifiedEpochMillis = it.longOrNull(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
            )
        }
    }

    private fun probeCapability(
        resolver: ContentResolver,
        uri: Uri,
        probe: SeekabilityProbe,
    ): ProbeResult = try {
        resolver.openFileDescriptor(uri, "r")?.use { probe.probe(it) }
            ?: ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.DESCRIPTOR_UNUSABLE)
    } catch (e: Exception) {
        ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.DESCRIPTOR_UNUSABLE)
    }
}
