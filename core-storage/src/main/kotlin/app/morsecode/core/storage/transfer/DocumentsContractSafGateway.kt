package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException

/*
 * The production SAF gateway.
 *
 * The only class in this module that talks to a ContentResolver. Everything
 * above it -- the coordinator, the containment resolver, the identity policy --
 * works against narrow interfaces so that the whole commit sequence can be
 * exercised deterministically. That separation is worth keeping strictly: the
 * moment a caller reaches past the gateway for a query, the reasoning about
 * descriptors, grants and typed failures stops being testable.
 *
 * Two rules shape every method here.
 *
 * First, a failed answer is never the same as an absent one. A null cursor, a
 * thrown query and a missing row are three different facts with three different
 * recoveries, and collapsing them is how a transient provider fault gets read
 * as "the file was never created".
 *
 * Second, the provider is not a filesystem. It can decline to answer, return
 * null where it promised a Uri, hand back a different identity than the one
 * asked for, and revoke the grant between two consecutive calls. So every
 * operation maps its failures to typed errors, and a SecurityException is
 * always a revocation -- never "missing", never a generic provider failure.
 */

/** The outcome of asking a provider for a directory's children. */
public sealed interface SafChildNames {
    public data class Listed(public val names: Set<String>) : SafChildNames
    public data class Failed(public val error: TransferStorageError) : SafChildNames
}

/**
 * Production gateway over `ContentResolver`, `DocumentsContract` and
 * `ParcelFileDescriptor`.
 *
 * Implements both provider seams: [SafDocumentGateway] for document lifecycle
 * and [SafContainmentProver] for the descendant questions containment asks.
 *
 * The API tier is injected rather than an SDK integer, so that a test can
 * supply a fake tier but cannot talk the gateway into calling a method the
 * running device does not have. See [SafPlatformOperations].
 */
public class DocumentsContractSafGateway(
    private val resolver: ContentResolver,
    /**
     * The platform tier the gateway asks.
     *
     * Defaults to the tier selected from the real Build.VERSION.SDK_INT. It is
     * a parameter so tests can inject a fake, but a fake replaces the whole
     * tier rather than the number that selects one, so a test cannot direct
     * production code at a call the running device does not have.
     */
    private val operations: SafPlatformOperations = SafPlatformOperations.create(),
) : SafDocumentGateway, SafContainmentProver {

    // -----------------------------------------------------------------------
    // Grants
    // -----------------------------------------------------------------------

    /**
     * Whether a persisted grant for [treeUri] is still held.
     *
     * Asked again before every destructive or write operation, because a grant
     * is not a fact that survives the whole commit: the user can revoke it in
     * Settings between two calls.
     */
    public fun hasPersistedGrant(treeUri: Uri, write: Boolean): Boolean = try {
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri &&
                if (write) permission.isWritePermission else permission.isReadPermission
        }
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    /**
     * Resolves a persisted grant row into a [SafTreeGrant].
     *
     * Returns null when the authority does not match, when the tree URI has no
     * parseable root document, or when the persisted grant is gone. The
     * returned grant reports `writable` from the live permission state, not
     * from the row, because only the platform knows what is still held.
     */
    public fun resolveGrant(
        grantId: String,
        treeUri: String,
        authority: String,
    ): SafTreeGrant? {
        val uri = treeUri.toUri()
        if (uri.authority != authority) return null
        val rootDocumentId = SafContainment.treeDocumentIdOf(uri) ?: return null
        return SafTreeGrant(
            grantId = grantId,
            treeUri = uri,
            rootDocumentId = rootDocumentId,
            authority = authority,
            writable = hasPersistedGrant(uri, write = true),
            // Deliberately not derived from findDocumentPath: its Path.getRootId
            // is documented to return null, and probing for it would cost a
            // provider round trip per grant resolution to learn nothing.
            rootId = null,
        )
    }

    // -----------------------------------------------------------------------
    // Containment — the platform's own descendant questions
    // -----------------------------------------------------------------------

    override fun isChildDocument(
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer = operations.isChildDocument(resolver, parentDocumentUri, childDocumentUri)

    override fun documentPath(documentUri: Uri): SafPathAnswer =
        operations.documentPath(resolver, documentUri)

    /**
     * Whether this device's tier can ask the provider anything about
     * containment, and which tier it is.
     *
     * Exposed because a caller that knows the tier can skip asking: below API
     * 26 the answer is always indeterminate, and asking anyway costs a round
     * trip to a provider that will not be questioned.
     */
    public val containmentTier: SafContainmentTier get() = operations.tier

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    override fun query(documentUri: String): SafLookup = queryInternal(documentUri.toUri())

    override fun findChild(parentUri: String, displayName: String): SafLookup {
        val childUri = SafContainment.documentUriUsingTree(
            parentUri.toUri(),
            // The child id is the parent id plus the name, which is how a tree
            // document URI addresses a descendant. Providers that address
            // children differently simply will not find it, and "not found" is
            // the correct answer for a collision check either way.
            childIdOf(parentUri, displayName),
        ) ?: return SafLookup.Failed(
            TransferStorageError.Unsupported("saf_document_uri"),
        )
        return queryInternal(childUri)
    }

    /** The display names of a directory's children, for collision detection. */
    public fun childNames(parentUri: String): SafChildNames {
        val parent = parentUri.toUri()
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent,
                SafContainment.documentIdOf(parent) ?: return SafChildNames.Failed(
                    TransferStorageError.Unsupported("saf_document_uri"),
                ),
            )
        } catch (e: Exception) {
            return SafChildNames.Failed(TransferStorageError.ProviderFailure("document_provider"))
        }

        val cursor = try {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
        } catch (e: SecurityException) {
            return SafChildNames.Failed(TransferStorageError.PermissionRevoked("read"))
        } catch (e: Exception) {
            return SafChildNames.Failed(TransferStorageError.ProviderFailure("document_provider"))
        }

        // A null cursor is the provider failing to answer, not an empty folder.
            ?: return SafChildNames.Failed(TransferStorageError.ProviderFailure("document_provider"))

        return cursor.use { c ->
            val names = HashSet<String>()
            val index = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0) return@use SafChildNames.Failed(
                TransferStorageError.ProviderFailure("document_provider"),
            )
            while (c.moveToNext()) {
                val name = c.getString(index)
                if (!name.isNullOrEmpty()) names += name
            }
            SafChildNames.Listed(names)
        }
    }

    /** The capabilities a directory advertises, from its own flags column. */
    public fun capabilitiesOf(parentUri: String): SafProviderCapabilities {
        val parent = when (val looked = queryInternal(parentUri.toUri())) {
            is SafLookup.Found -> looked.document
            is SafLookup.Absent -> return SafProviderCapabilities.UNKNOWN
            is SafLookup.Failed -> return SafProviderCapabilities.UNKNOWN
        }
        return SafProviderCapabilities.fromFlags(
            parentFlags = parent.flags,
            documentFlags = parent.flags,
        )
    }

    private fun queryInternal(uri: Uri): SafLookup {
        val cursor = try {
            resolver.query(uri, QUERY_COLUMNS, null, null, null)
        } catch (e: SecurityException) {
            // A revoked grant is not "the document is gone".
            return SafLookup.Failed(TransferStorageError.PermissionRevoked("read"))
        } catch (e: FileNotFoundException) {
            return SafLookup.Failed(TransferStorageError.NotFound("staged_copy"))
        } catch (e: Exception) {
            return SafLookup.Failed(TransferStorageError.ProviderFailure("document_provider"))
        }

        // A null cursor is the provider declining to answer. Treating this as
        // "missing" is the mistake that makes a transient fault look like a
        // document that was never created.
            ?: return SafLookup.Failed(TransferStorageError.ProviderFailure("document_provider"))

        return cursor.use { c ->
            // Absent only after a query that succeeded and returned no row.
            if (!c.moveToFirst()) return@use SafLookup.Absent
            readRow(uri, c)
        }
    }

    private fun readRow(uri: Uri, cursor: Cursor): SafLookup {
        val documentId =
            cursor.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                ?: SafContainment.documentIdOf(uri)
        val displayName =
            cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        // A row with no name and no identity is inconsistent, not absent:
        // something answered, and what it said does not describe a document.
        if (documentId.isNullOrEmpty() || displayName.isNullOrEmpty()) {
            return SafLookup.Failed(
                TransferStorageError.ProviderFailure(
                    "document_provider",
                    diagnostic = "row is missing its identity",
                ),
            )
        }

        val size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
        val flags = cursor.intOrNull(DocumentsContract.Document.COLUMN_FLAGS)
        val mime = cursor.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)

        return SafLookup.Found(
            SafDocumentInfo(
                documentUri = uri.toString(),
                documentId = documentId,
                displayName = displayName,
                // null means the provider did not say, which is not zero.
                sizeBytes = size,
                mimeType = mime,
                flags = flags,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun create(
        parentUri: String,
        mimeType: String,
        displayName: String,
    ): SafCreate {
        val parent = parentUri.toUri()
        return try {
            val created = DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
                // A null return means the provider declined; it is not a
                // document that now exists.
                ?: return SafCreate.Failed(
                    TransferStorageError.ProviderFailure(
                        "document_provider",
                        diagnostic = "createDocument returned null",
                    ),
                )

            val documentId = SafContainment.documentIdOf(created)
                ?: return SafCreate.Failed(
                    TransferStorageError.ProviderFailure(
                        "document_provider",
                        diagnostic = "created uri carries no document id",
                    ),
                )

            // The provider may have renamed it to resolve a collision, so the
            // recorded name is whatever the provider says it is now.
            val actualName = when (val looked = queryInternal(created)) {
                is SafLookup.Found -> looked.document.displayName
                else -> displayName
            }

            SafCreate.Created(created.toString(), documentId, actualName)
        } catch (e: SecurityException) {
            SafCreate.Failed(TransferStorageError.PermissionRevoked("write"))
        } catch (e: Exception) {
            SafCreate.Failed(mapFailure(e, "create"))
        }
    }

    override fun openWrite(documentUri: String): SafOpen {
        val uri = documentUri.toUri()
        val descriptor = try {
            resolver.openFileDescriptor(uri, WRITE_MODE)
                ?: return SafOpen.Refused(
                    TransferStorageError.ProviderFailure(
                        "document_provider",
                        diagnostic = "openFileDescriptor returned null",
                    ),
                )
        } catch (e: SecurityException) {
            return SafOpen.Refused(TransferStorageError.PermissionRevoked("write"))
        } catch (e: Exception) {
            return SafOpen.Refused(mapFailure(e, "open"))
        }
        // One descriptor, one owning stream. AutoCloseOutputStream closes the
        // descriptor when the stream is closed, so there is exactly one owner
        // and closing it once is correct.
        return SafOpen.Opened(
            SafWriteHandle(descriptor, ParcelFileDescriptor.AutoCloseOutputStream(descriptor)),
        )
    }

    override fun openRead(documentUri: String): SafOpen {
        val uri = documentUri.toUri()
        val descriptor = try {
            resolver.openFileDescriptor(uri, READ_MODE)
                ?: return SafOpen.Refused(
                    TransferStorageError.ProviderFailure(
                        "document_provider",
                        diagnostic = "openFileDescriptor returned null",
                    ),
                )
        } catch (e: SecurityException) {
            return SafOpen.Refused(TransferStorageError.PermissionRevoked("read"))
        } catch (e: Exception) {
            return SafOpen.Refused(mapFailure(e, "open"))
        }
        return SafOpen.Opened(
            SafReadHandle(ParcelFileDescriptor.AutoCloseInputStream(descriptor)),
        )
    }

    override fun rename(documentUri: String, displayName: String): SafRename {
        val uri = documentUri.toUri()
        return try {
            val renamed = DocumentsContract.renameDocument(resolver, uri, displayName)
            // A null return is not a rename that kept its old identity. It is
            // the provider refusing to say what happened, and only
            // reconciliation can settle it.
                ?: return SafRename.Failed(
                    TransferStorageError.ProviderFailure(
                        "document_provider",
                        diagnostic = "renameDocument returned null",
                    ),
                )
            SafRename.Renamed(renamed.toString())
        } catch (e: SecurityException) {
            SafRename.Failed(TransferStorageError.PermissionRevoked("write"))
        } catch (e: Exception) {
            SafRename.Failed(mapFailure(e, "rename"))
        }
    }

    override fun delete(documentUri: String): SafDelete {
        val uri = documentUri.toUri()
        return try {
            val deleted = DocumentsContract.deleteDocument(resolver, uri)
            // Zero rows affected is not an error the caller should treat as
            // success, and it is not proof the document is gone either.
            if (deleted) SafDelete.Deleted else SafDelete.Absent
        } catch (e: SecurityException) {
            SafDelete.Failed(TransferStorageError.PermissionRevoked("write"))
        } catch (e: FileNotFoundException) {
            SafDelete.Absent
        } catch (e: Exception) {
            SafDelete.Failed(mapFailure(e, "delete"))
        }
    }

    /**
     * Deletes an exact stored identity and then proves it is gone.
     *
     * The boolean from [DocumentsContract.deleteDocument] is recorded and then
     * ignored. It is not the provider answering "is it gone" -- measured on a
     * real provider round trip, it reports success whether or not anything was
     * removed -- so letting it decide would close cleanup against a claim
     * nobody verified. What decides is the follow-up query on the same stored
     * URI: only a query that completed and returned no row proves absence.
     *
     * [grant] is optional. When supplied it is re-checked first, because a
     * grant can be revoked between the decision to clean up and the cleanup.
     * When it is null the caller is asserting it has already authorized, and
     * the gateway will still classify a revocation it meets on the way.
     */
    override fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant?,
    ): SafDeletion {
        val uri = documentUri.toUri()

        // 1. Authorize. A cleanup that outlives its grant is not a cleanup, it
        // is a SecurityException waiting to be misread as a missing document.
        if (grant != null) {
            val observedAuthority = uri.authority
            if (observedAuthority != grant.authority) {
                return SafDeletion.IdentityMismatch(
                    expectedDocumentId = expectedDocumentId,
                    observedDocumentId = observedAuthority ?: "",
                    observedDisplayName = null,
                )
            }
            if (!hasPersistedGrant(grant.treeUri, write = true)) {
                return SafDeletion.PermissionRevoked(
                    TransferStorageError.PermissionRevoked("write"),
                )
            }
        }

        // 2. Request deletion. A throw here is a failed request, not an
        // absence: nothing has been observed about the document yet.
        val deleteReported = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: SecurityException) {
            return SafDeletion.PermissionRevoked(
                TransferStorageError.PermissionRevoked("write"),
            )
        } catch (e: Exception) {
            return SafDeletion.DeleteRequestFailed(mapFailure(e, "delete"))
        }

        // 3 and 4. Query the exact stored identity, then classify what was
        // observed. The delete result is carried as a diagnostic only.
        return when (val looked = queryInternal(uri)) {
            // A query that completed and found no row is the only proof.
            is SafLookup.Absent -> SafDeletion.ConfirmedAbsent(deleteReported = deleteReported)

            is SafLookup.Found -> {
                val observedId = looked.document.documentId
                if (observedId == expectedDocumentId) {
                    SafDeletion.StillPresent(
                        document = looked.document,
                        deleteReported = deleteReported,
                    )
                } else {
                    SafDeletion.IdentityMismatch(
                        expectedDocumentId = expectedDocumentId,
                        observedDocumentId = observedId,
                        observedDisplayName = looked.document.displayName,
                    )
                }
            }

            is SafLookup.Failed -> when (val error = looked.error) {
                is TransferStorageError.PermissionRevoked ->
                    SafDeletion.PermissionRevoked(error, deleteReported = deleteReported)

                else -> SafDeletion.QueryUnknown(
                    diagnostic = "follow-up query did not complete",
                    deleteReported = deleteReported,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Failure mapping
    // -----------------------------------------------------------------------

    /**
     * Maps a provider exception to a typed storage error.
     *
     * Order matters: a SecurityException is a revocation before it is anything
     * else, and a storage-full condition has to be recognised from the message
     * because the platform reports it as an ordinary IOException with an errno.
     */
    private fun mapFailure(error: Exception, operation: String): TransferStorageError = when {
        StorageFailureClassifier.isRevocation(error) ->
            TransferStorageError.PermissionRevoked("write")

        StorageFailureClassifier.isStorageFull(error) ->
            TransferStorageError.StorageFull(operation)

        error is FileNotFoundException -> TransferStorageError.NotFound("staged_copy")
        error is IOException -> TransferStorageError.Io(operation)
        else -> TransferStorageError.ProviderFailure("document_provider")
    }

    private companion object {
        const val WRITE_MODE = "w"
        const val READ_MODE = "r"

        val QUERY_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

/**
 * The document id a child of [parentUri] named [displayName] would have.
 *
 * A tree document URI addresses descendants as `parent-id/name`, so a collision
 * check is an ordinary query on the id rather than a scan of the directory.
 */
private fun childIdOf(parentUri: String, displayName: String): String {
    val parentId = SafContainment.documentIdOf(parentUri.toUri()) ?: return displayName
    return if (parentId.isEmpty()) displayName else "$parentId/$displayName"
}


