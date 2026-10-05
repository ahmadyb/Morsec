package app.morsecode.core.storage.transfer

import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * A gateway that remembers what was asked of it, in order.
 *
 * The order is the thing under test, so this records it rather than modelling a
 * provider. A fake that faithfully reproduced one provider's behaviour would
 * tell us about that provider; what has to be true of every provider is the
 * sequence: authorize, create, copy, flush, close, query, reopen, verify, rename.
 *
 * Two deliberate choices shape it.
 *
 * It turns a failure into a typed result rather than throwing, because that is
 * what the real gateway does. A fake that threw would be testing a path the
 * production code never takes: the coordinator does not catch, on the
 * understanding that the gateway already did. Exercising a throw here would be
 * exercising a crash, and would say nothing about the classification that
 * actually decides SecurityException from a full medium.
 *
 * Closes are recorded from the stream rather than from the handle. The handle is
 * the owner and its close is final, so the only seam a test can observe is the
 * stream the handle closes -- which is also the honest place to observe it,
 * because it is the stream closing that releases the bytes.
 *
 * The handle constructors are internal to the module, which is deliberate: a
 * descriptor is only ever minted by the gateway that owns it.
 */

internal class RecordingSafGateway(
    /** Bytes by document uri. Pre-populate to make a document exist. */
    private val contents: MutableMap<String, ByteArray> = mutableMapOf(),
    /** Whether a rename hands back a new uri, the same one, or nothing. */
    var renameReturns: RenameReturn = RenameReturn.SAME,
    /** Set to make every query fail with this error. */
    var queryFailure: TransferStorageError? = null,
    /** A provider that omits size metadata, so exact length must be read. */
    var omitSizeOnQuery: Boolean = false,
    /** Use this reported size independently of the bytes opened for reading. */
    var overrideReportedSize: Boolean = false,
    var reportedSizeBytes: Long? = null,
    /** Set to make a rename fail. */
    var renameFailure: TransferStorageError? = null,
    /** Set to make a create fail. */
    var createFailure: TransferStorageError? = null,
    /** Set to make an open fail. */
    var openFailure: TransferStorageError? = null,
    /** What a delete-and-reconcile observes. */
    var deletionObserved: SafDeletion = SafDeletion.ConfirmedAbsent(),

    /**
     * Throws from the named operation, so a test can put a failure at one point
     * in the sequence.
     *
     * Named rather than positional because the interesting failures are the late
     * ones: storage filling up during the copy and the grant being revoked
     * between two calls are different events landing on different operations, and
     * a flag that made everything fail at once could not tell them apart. The
     * throw is then classified into a typed result, exactly as the gateway would.
     */
    var throwOn: ((String) -> Throwable?)? = null,

    /** A rename that copies rather than moves, leaving both identities behind. */
    var renameKeepsOriginal: Boolean = false,

    /** A rename whose returned identity does not resolve. */
    var renameDropsReturned: Boolean = false,

    /** The provider may assign a different display name than the requested one. */
    var renameDisplayNameOverride: ((String) -> String)? = null,

    /** Injects a close error after the fake stream has released its backing buffer. */
    var closeFailureOn: ((String) -> Throwable?)? = null,

    /** Injects a live-grant failure at a named SAF operation. */
    var grantFailureOn: ((SafContainmentOperation) -> TransferStorageError?)? = null,

    /** Callbacks run after the corresponding provider side effect/read. */
    var afterCreate: (() -> Unit)? = null,
    var afterRename: (() -> Unit)? = null,
    var afterQuery: ((String) -> Unit)? = null,
    var afterStreamWrite: (() -> Unit)? = null,
    var afterStreamRead: (() -> Unit)? = null,
    var afterOpenWrite: (() -> Unit)? = null,
    var afterOpenRead: (() -> Unit)? = null,
    var afterDelete: ((String) -> Unit)? = null,
    var afterFlush: (() -> Unit)? = null,

    /** Injects an IOException after a prefix of a single write has landed. */
    var partialWriteFailureAfterBytes: Long? = null,
    var partialWriteFailure: Throwable? = null,

    /** Failure at a selected rename call, used for backup/promotion matrices. */
    var failRenameAttempt: Int? = null,
    var renameAttemptFailure: TransferStorageError? = null,

    /** Failure from descriptor sync, after OutputStream.flush succeeds. */
    var syncFailure: Throwable? = null,
) : SafDocumentGateway {

    /** The operations that can be made to fail, for a test that walks them. */
    companion object {
        const val OP_FIND_CHILD = "findChild"
        const val OP_CREATE = "create"
        const val OP_RENAME = "rename"
        const val OP_QUERY = "query"
        const val OP_OPEN_WRITE = "openWrite"
        const val OP_OPEN_READ = "openRead"
        const val OP_DELETE = "deleteAndReconcile"
        const val OP_STREAM_READ = "streamRead"
        const val OP_STREAM_WRITE = "streamWrite"
        const val OP_FLUSH = "flush"
        const val OP_CLOSE_WRITE = "closeWrite"
        const val OP_CLOSE_READ = "closeRead"

        /** A throw with this message classifies as a full medium. */
        fun storageFull(): IOException = IOException("write failed: ENOSPC (No space left on device)")
    }

    /** Every operation, in the order it happened. */
    val calls: MutableList<String> = mutableListOf()

    /** Count of every fake provider/gateway call, including grant rechecks. */
    val providerCallCount: Int get() = calls.size

    /** Bytes written per uri, so a test can say what landed where. */
    val written: MutableMap<String, ByteArray> = mutableMapOf()

    /** How many handles were opened and how many of those were closed. */
    var openedHandles = 0
        private set

    var closedHandles = 0
        private set

    /** Handles still open right now. A leak-free sequence ends at zero. */
    val liveHandles: Int get() = openedHandles - closedHandles

    /** Documents that exist, by uri. */
    val existing: MutableSet<String> = contents.keys.toMutableSet()

    /** Documents deliberately omitted from name lookup, to model a moved/stale child. */
    val hiddenFromChildListing: MutableSet<String> = mutableSetOf()

    /** Makes each new document unreachable from its parent listing for containment tests. */
    var hideCreatedDocumentsFromChildListing: Boolean = false

    /** Display name by uri. Defaults to the last path segment. */
    private val names: MutableMap<String, String> = mutableMapOf()

    /** The uri a create returned most recently. */
    var lastCreatedUri: String? = null
        private set

    private var nextDocumentId = 1
    private var renameAttempts = 0
    private var partialWriteFailureTriggered = false

    /** Keep the tree-grant root and provider document identity in the platform URI shape. */
    private fun documentUriFor(parentOrTreeUri: String, documentId: String): String {
        val parentUri = Uri.parse(parentOrTreeUri)
        val authority = requireNotNull(parentUri.authority)
        val treeDocumentId = requireNotNull(
            runCatching { DocumentsContract.getTreeDocumentId(parentUri) }.getOrNull(),
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
    }

    /** Which identity a rename hands back. */
    enum class RenameReturn { SAME, NEW, NULL }

    /**
     * Puts a document under [displayName] so a duplicate is actually found.
     *
     * Without an explicit name a seeded uri's name is its id, and a lookup by the
     * final name finds nothing -- which silently turns every duplicate test into
     * a first-write test.
     */
    fun addNamed(uri: String, displayName: String) {
        names[uri] = displayName
        existing += uri
        contents.putIfAbsent(uri, ByteArray(0))
    }

    // -- Recording and failure injection -------------------------------------

    private fun record(call: String) {
        calls += call
    }

    private fun fail(op: String) {
        throwOn?.invoke(op)?.let { throw it }
    }

    private fun failClose(op: String) {
        closeFailureOn?.invoke(op)?.let { throw it }
    }

    /** Classifies a throw the way the gateway would, so the outcome is typed. */
    private fun mapError(error: Throwable): TransferStorageError =
        StorageFailureClassifier.classifyOrProviderFailure(error, "provider")

    private inline fun <T> guarded(
        op: String,
        orElse: (TransferStorageError) -> T,
        block: () -> T,
    ): T = try {
        fail(op)
        block()
    } catch (e: Exception) {
        orElse(mapError(e))
    }

    /** The index of [call] in the recorded order, or -1. */
    fun indexOfFirst(predicate: (String) -> Boolean): Int = calls.indexOfFirst(predicate)

    fun countOf(prefix: String): Int = calls.count { it.startsWith(prefix) }

    // -- SafDocumentGateway --------------------------------------------------

    override fun recheckPersistedGrant(
        grant: SafTreeGrant,
        operation: SafContainmentOperation,
    ): TransferStorageError? {
        record("authorize:${operation.id}")
        return grantFailureOn?.invoke(operation)
    }

    override fun findChild(parentUri: String, displayName: String): SafLookup {
        record("findChild:$parentUri:$displayName")
        return guarded(OP_FIND_CHILD, { SafLookup.Failed(it) }) {
            queryFailure?.let { return SafLookup.Failed(it) }
            val hit = existing.firstOrNull {
                it !in hiddenFromChildListing && nameOf(it) == displayName
            } ?: return SafLookup.Absent
            SafLookup.Found(info(hit))
        }
    }

    override fun create(
        parentUri: String,
        mimeType: String,
        displayName: String,
    ): SafCreate {
        record("create:$parentUri:$displayName")
        return guarded(OP_CREATE, { SafCreate.Failed(it) }) {
            createFailure?.let { return SafCreate.Failed(it) }
            val id = "doc-${nextDocumentId++}"
            val uri = documentUriFor(parentUri, id)
            existing += uri
            names[uri] = displayName
            if (hideCreatedDocumentsFromChildListing) hiddenFromChildListing += uri
            contents[uri] = ByteArray(0)
            written[uri] = ByteArray(0)
            lastCreatedUri = uri
            afterCreate?.invoke()
            SafCreate.Created(uri, id, displayName)
        }
    }

    override fun rename(documentUri: String, displayName: String): SafRename {
        record("rename:$documentUri:$displayName")
        val attempt = ++renameAttempts
        return guarded(OP_RENAME, { SafRename.Failed(it) }) {
            if (failRenameAttempt == attempt) {
                return@guarded SafRename.Failed(renameAttemptFailure ?: TransferStorageError.StorageFull("rename"))
            }
            renameFailure?.let { return SafRename.Failed(it) }
            val actualDisplayName = renameDisplayNameOverride?.invoke(displayName) ?: displayName
            val newUri = when (renameReturns) {
                RenameReturn.SAME -> documentUri
                RenameReturn.NEW -> {
                    val id = "doc-${nextDocumentId++}"
                    val uri = documentUriFor(documentUri, id)
                    contents[uri] = contents[documentUri] ?: ByteArray(0)
                    written[uri] = written[documentUri] ?: ByteArray(0)
                    uri
                }

                RenameReturn.NULL -> null
            }
            if (newUri == null || newUri == documentUri) {
                names[documentUri] = actualDisplayName
            } else {
                names[newUri] = actualDisplayName
                if (!renameDropsReturned) existing += newUri
                if (!renameKeepsOriginal) existing -= documentUri
            }
            record(if (newUri == null) "rename-result:null" else "rename-result:returned")
            afterRename?.invoke()
            SafRename.Renamed(newUri)
        }
    }

    override fun delete(documentUri: String): SafDelete {
        record("delete:$documentUri")
        return guarded(OP_DELETE, { SafDelete.Failed(it) }) {
            existing -= documentUri
            SafDelete.Deleted
        }
    }

    override fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant?,
    ): SafDeletion {
        record("deleteAndReconcile:$documentUri:$expectedDocumentId")
        val uriDocumentId = SafContainment.documentIdOf(android.net.Uri.parse(documentUri))
        if (uriDocumentId == null) {
            return SafDeletion.DeleteRequestFailed(TransferStorageError.Unsupported("saf_document_uri"))
        }
        if (uriDocumentId != expectedDocumentId) {
            return SafDeletion.IdentityMismatch(expectedDocumentId, uriDocumentId)
        }
        if (grant != null) {
            when (val authorization = recheckPersistedGrant(
                grant,
                SafContainmentOperation.DELETE_TEMPORARY,
            )) {
                is TransferStorageError.PermissionRevoked ->
                    return SafDeletion.PermissionRevoked(authorization)

                null -> Unit
                else -> return SafDeletion.DeleteRequestFailed(authorization)
            }
        }
        return try {
            fail(OP_DELETE)
            if (deletionObserved is SafDeletion.ConfirmedAbsent) existing -= documentUri
            afterDelete?.invoke(documentUri)
            deletionObserved
        } catch (error: Exception) {
            when (val mapped = mapError(error)) {
                is TransferStorageError.PermissionRevoked -> SafDeletion.PermissionRevoked(mapped)
                else -> SafDeletion.QueryUnknown(mapped::class.simpleName.orEmpty())
            }
        }
    }

    override fun query(documentUri: String): SafLookup {
        record("query:$documentUri")
        afterQuery?.invoke(documentUri)
        return guarded(OP_QUERY, { SafLookup.Failed(it) }) {
            queryFailure?.let { return SafLookup.Failed(it) }
            if (documentUri !in existing) return SafLookup.Absent
            SafLookup.Found(info(documentUri))
        }
    }

    override fun openWrite(documentUri: String): SafOpen {
        record("openWrite:$documentUri")
        return guarded(OP_OPEN_WRITE, { SafOpen.Refused(it) }) {
            openFailure?.let { return SafOpen.Refused(it) }
            openedHandles++
            val sink = RecordingOutputStream(documentUri) { bytes ->
                written[documentUri] = bytes
            }
            val handle = SafWriteHandle(null, sink) {
                syncFailure?.let { throw it }
            }
            afterOpenWrite?.invoke()
            SafOpen.Opened(handle)
        }
    }

    override fun openRead(documentUri: String): SafOpen {
        record("openRead:$documentUri")
        return guarded(OP_OPEN_READ, { SafOpen.Refused(it) }) {
            openFailure?.let { return SafOpen.Refused(it) }
            openedHandles++
            val bytes = written[documentUri] ?: contents[documentUri] ?: ByteArray(0)
            afterOpenRead?.invoke()
            SafOpen.Opened(SafReadHandle(RecordingInputStream(documentUri, bytes)))
        }
    }

    // -- Helpers ------------------------------------------------------------

    private fun info(uri: String): SafDocumentInfo = SafDocumentInfo(
        documentUri = uri,
        documentId = requireNotNull(SafContainment.documentIdOf(Uri.parse(uri))),
        displayName = nameOf(uri),
        sizeBytes = when {
            omitSizeOnQuery -> null
            overrideReportedSize -> reportedSizeBytes
            else -> (written[uri] ?: contents[uri])?.size?.toLong()
        },
        mimeType = "application/octet-stream",
        flags = 0,
        isDirectory = false,
    )

    private fun nameOf(uri: String): String = names[uri] ?: uri.substringAfterLast('/')

    private inner class RecordingOutputStream(
        private val uri: String,
        private val onClose: (ByteArray) -> Unit,
    ) : OutputStream() {
        private val sink = ByteArrayOutputStream()

        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            record("write:$uri:$len")
            fail(OP_STREAM_WRITE)
            val failAfter = partialWriteFailureAfterBytes
            val landed = sink.size().toLong()
            if (!partialWriteFailureTriggered && failAfter != null && landed + len > failAfter) {
                val accepted = (failAfter - landed).coerceIn(0L, len.toLong()).toInt()
                if (accepted > 0) sink.write(b, off, accepted)
                partialWriteFailureTriggered = true
                afterStreamWrite?.invoke()
                throw (partialWriteFailure ?: storageFull())
            }
            sink.write(b, off, len)
            afterStreamWrite?.invoke()
        }

        override fun flush() {
            record("flush:$uri")
            fail(OP_FLUSH)
            sink.flush()
            afterFlush?.invoke()
        }

        override fun close() {
            record("closeWrite:$uri")
            closedHandles++
            onClose(sink.toByteArray())
            sink.close()
            failClose(OP_CLOSE_WRITE)
        }
    }

    private inner class RecordingInputStream(
        private val uri: String,
        bytes: ByteArray,
    ) : InputStream() {
        private val source = ByteArrayInputStream(bytes)

        override fun read(): Int {
            record("read:$uri:1")
            fail(OP_STREAM_READ)
            return source.read().also { afterStreamRead?.invoke() }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            record("read:$uri:$len")
            fail(OP_STREAM_READ)
            return source.read(b, off, len).also { afterStreamRead?.invoke() }
        }

        override fun close() {
            record("closeRead:$uri")
            closedHandles++
            source.close()
            failClose(OP_CLOSE_READ)
        }
    }
}
