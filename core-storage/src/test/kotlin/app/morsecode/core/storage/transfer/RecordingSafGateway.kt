package app.morsecode.core.storage.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    /** Set to make the next N queries fail with this error. */
    var queryFailure: TransferStorageError? = null,
    /** Set to make a rename fail. */
    var renameFailure: TransferStorageError? = null,
    /** Set to make a create fail. */
    var createFailure: TransferStorageError? = null,
    /** Set to make an open fail. */
    var openFailure: TransferStorageError? = null,
    /** What a delete-and-reconcile observes. */
    var deletionObserved: SafDeletion = SafDeletion.ConfirmedAbsent(),
) : SafDocumentGateway {

    /** Every operation, in the order it happened. */
    val calls: MutableList<String> = mutableListOf()

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

    /** The uri a create returned most recently. */
    var lastCreatedUri: String? = null
        private set

    private var nextDocumentId = 1

    /** Which identity a rename hands back. */
    enum class RenameReturn { SAME, NEW, NULL }

    // -- Recording ----------------------------------------------------------

    private fun record(call: String) {
        calls += call
    }

    /** The index of [call] in the recorded order, or -1. */
    fun indexOf(call: String): Int = calls.indexOf(call)

    fun indexOfFirst(predicate: (String) -> Boolean): Int = calls.indexOfFirst(predicate)

    fun countOf(prefix: String): Int = calls.count { it.startsWith(prefix) }

    override fun findChild(parentUri: String, displayName: String): SafLookup {
        record("findChild:$parentUri:$displayName")
        queryFailure?.let { return SafLookup.Failed(it) }
        val hit = existing.firstOrNull { nameOf(it) == displayName }
            ?: return SafLookup.Absent
        return SafLookup.Found(info(hit))
    }

    override fun create(
        parentUri: String,
        mimeType: String,
        displayName: String,
    ): SafCreate {
        record("create:$parentUri:$displayName")
        createFailure?.let { return SafCreate.Failed(it) }
        val id = "doc-${nextDocumentId++}"
        val uri = "$parentUri/document/$id"
        existing += uri
        contents[uri] = ByteArray(0)
        written[uri] = ByteArray(0)
        lastCreatedUri = uri
        return SafCreate.Created(uri, id, displayName)
    }

    override fun rename(documentUri: String, displayName: String): SafRename {
        record("rename:$documentUri:$displayName")
        renameFailure?.let { return SafRename.Failed(it) }
        val newUri = when (renameReturns) {
            RenameReturn.SAME -> documentUri
            RenameReturn.NEW -> {
                val id = "doc-${nextDocumentId++}"
                val parent = documentUri.substringBefore("/document/")
                val uri = "$parent/document/$id"
                // A rename that copies rather than moves leaves both behind.
                contents[uri] = contents[documentUri] ?: ByteArray(0)
                written[uri] = written[documentUri] ?: ByteArray(0)
                existing += uri
                uri
            }

            RenameReturn.NULL -> null
        }
        if (newUri != null && newUri != documentUri) {
            existing -= documentUri
        }
        return SafRename.Renamed(newUri)
    }

    override fun delete(documentUri: String): SafDelete {
        record("delete:$documentUri")
        existing -= documentUri
        return SafDelete.Deleted
    }

    override fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant?,
    ): SafDeletion {
        record("deleteAndReconcile:$documentUri:$expectedDocumentId")
        if (deletionObserved is SafDeletion.ConfirmedAbsent) existing -= documentUri
        return deletionObserved
    }

    override fun query(documentUri: String): SafLookup {
        record("query:$documentUri")
        queryFailure?.let { return SafLookup.Failed(it) }
        if (documentUri !in existing) return SafLookup.Absent
        return SafLookup.Found(info(documentUri))
    }

    override fun openWrite(documentUri: String): SafOpen {
        record("openWrite:$documentUri")
        openFailure?.let { return SafOpen.Refused(it) }
        openedHandles++
        val sink = RecordingOutputStream(documentUri) { bytes ->
            written[documentUri] = bytes
        }
        return SafOpen.Opened(SafWriteHandle(null, sink))
    }

    override fun openRead(documentUri: String): SafOpen {
        record("openRead:$documentUri")
        openFailure?.let { return SafOpen.Refused(it) }
        openedHandles++
        val bytes = written[documentUri] ?: contents[documentUri] ?: ByteArray(0)
        val source = RecordingInputStream(documentUri, bytes)
        return SafOpen.Opened(SafReadHandle(source))
    }

    // -- Helpers ------------------------------------------------------------

    private fun info(uri: String): SafDocumentInfo = SafDocumentInfo(
        documentUri = uri,
        documentId = uri.substringAfterLast('/'),
        displayName = nameOf(uri),
        sizeBytes = (written[uri] ?: contents[uri])?.size?.toLong(),
        mimeType = "application/octet-stream",
        flags = 0,
        isDirectory = false,
    )

    private fun nameOf(uri: String): String =
        written[uri]?.let { "written" } ?: contents[uri]?.let { "seeded" } ?: uri.substringAfterLast('/')

    private inner class RecordingOutputStream(
        private val uri: String,
        private val onClose: (ByteArray) -> Unit,
    ) : OutputStream() {
        private val sink = ByteArrayOutputStream()

        override fun write(b: Int) {
            sink.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            sink.write(b, off, len)
        }

        override fun flush() {
            record("flush:$uri")
            sink.flush()
        }

        override fun close() {
            record("closeWrite:$uri")
            closedHandles++
            onClose(sink.toByteArray())
            sink.close()
        }
    }

    private inner class RecordingInputStream(
        private val uri: String,
        bytes: ByteArray,
    ) : InputStream() {
        private val source = ByteArrayInputStream(bytes)

        override fun read(): Int = source.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)

        override fun close() {
            record("closeRead:$uri")
            closedHandles++
            source.close()
        }
    }
}
