package app.morsecode.core.storage.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/*
 * A real ContentProvider, driven through a real ContentResolver.
 *
 * It exists so the tests exercise the same DocumentsContract code paths a
 * device would: createDocument and renameDocument are ContentResolver.call()
 * round trips, queries return real cursors, and descriptors are real
 * ParcelFileDescriptors over files on disk. A contract-only fake would prove
 * the coordinator's logic and nothing about whether the gateway asks the
 * platform the right questions.
 *
 * Two promises it must keep. The URIs it hands out are built from the authority
 * it is registered under, because a provider that invents an authority has
 * described a document no ContentResolver can find. And what it removes is
 * tracked separately from what it reports, because a provider is free to lie
 * about either and the gateway is required to believe neither.
 */

/**
 * The columns this provider answers with.
 *
 * The same set the gateway projects, so that a column the gateway asks for is a
 * column the provider populates, and a "missing column" can only come from the
 * provider declining rather than from the two halves of a test disagreeing
 * about the schema.
 */
private val COLUMN_ID = DocumentsContract.Document.COLUMN_DOCUMENT_ID
private val COLUMN_NAME = DocumentsContract.Document.COLUMN_DISPLAY_NAME
private val COLUMN_SIZE = DocumentsContract.Document.COLUMN_SIZE
private val COLUMN_MIME = DocumentsContract.Document.COLUMN_MIME_TYPE
private val COLUMN_FLAGS = DocumentsContract.Document.COLUMN_FLAGS

internal val TEST_COLUMNS = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS,
)

/**
 * How the provider behaves when asked to delete.
 *
 * Split into "removes" and "reports success" because those are separate facts
 * and the interesting cases are the ones where a provider disagrees with
 * itself. The gateway is required to settle deletion by observation, so it has
 * to be shown both directions of the lie.
 */
enum class DeleteBehaviour(
    val removes: Boolean,
    val reportsSuccess: Boolean,
) {
    /** Removes the document and says so. */
    HONEST(removes = true, reportsSuccess = true),

    /** Removes the document but reports that it did not. */
    REMOVES_BUT_REPORTS_FALSE(removes = true, reportsSuccess = false),

    /** Keeps the document but reports success. */
    KEEPS_BUT_REPORTS_TRUE(removes = false, reportsSuccess = true),

    /** Affects no row and says so. */
    NO_OP(removes = false, reportsSuccess = false),

    /** Throws. The request itself failed. */
    THROWS(removes = false, reportsSuccess = false),

    /** Throws SecurityException. The grant is gone. */
    THROWS_SECURITY(removes = false, reportsSuccess = false),
}

class FakeSafProvider : ContentProvider() {

    private data class Doc(
        val id: String,
        var name: String,
        val mime: String,
        val size: Long?,
        val flags: Int?,
        val parentId: String?,
    )

    private val documents = LinkedHashMap<String, Doc>()
    private val contents = LinkedHashMap<String, File>()
    private val calls = LinkedHashMap<String, Int>()
    var writeOpenCount: Int = 0
        private set
    private lateinit var storageDir: File

    /**
     * The tree every document URI this provider hands out is built from.
     *
     * It has to be the authority the provider is *registered* under. A
     * provider that invents its own authority in the Uri it returns has
     * described a document no ContentResolver can find, and every later
     * operation on that Uri -- open, query, rename, delete -- resolves to
     * nothing. That failure looks like a gateway bug and is not one.
     */
    private lateinit var treeBaseUri: Uri

    var nullCursor = false
    var throwOnQuery = false
    var throwSecurityOnQuery = false
    var nullCursorOnQueryNumber: Int? = null
    var throwOnQueryNumber: Int? = null
    var throwSecurityOnQueryNumber: Int? = null
    var omitDocumentIdOnQuery = false
    private var queryCount = 0
    var nullOnCreate = false
    var nullOnRename = false
    var nullOnOpen = false
    var throwSecurityOnCall = false
    var throwSecurityOnOpen = false
    var deleteBehaviour: DeleteBehaviour = DeleteBehaviour.HONEST

    /** Reported identity for the next query, overriding the document's own. */
    var queryDocumentIdOverride: String? = null

    /** What the provider answered to the last delete, null if never asked. */
    var lastDeleteRemoved: Boolean? = null
    var renameOnCreate = false
    var failOpenWith: RuntimeException? = null
    private val malformed = mutableSetOf<String>()

    fun reset(rootDocumentId: String, treeUri: Uri) {
        treeBaseUri = treeUri
        documents.clear(); contents.clear(); calls.clear(); malformed.clear()
        writeOpenCount = 0
        nullCursor = false; throwOnQuery = false; throwSecurityOnQuery = false
        nullCursorOnQueryNumber = null; throwOnQueryNumber = null
        throwSecurityOnQueryNumber = null; omitDocumentIdOnQuery = false; queryCount = 0
        nullOnCreate = false; nullOnRename = false; nullOnOpen = false
        throwSecurityOnCall = false; throwSecurityOnOpen = false
        queryDocumentIdOverride = null
        deleteBehaviour = DeleteBehaviour.HONEST; renameOnCreate = false; failOpenWith = null
        lastDeleteRemoved = null
        storageDir = File(context!!.filesDir, "fake-saf").apply { mkdirs() }
        documents[rootDocumentId] = Doc(
            rootDocumentId,
            "Download",
            DocumentsContract.Document.MIME_TYPE_DIR,
            null,
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            null,
        )
    }

    fun addDocument(
        id: String,
        name: String,
        mime: String = "video/mp4",
        size: Long? = 0L,
        flags: Int? = null,
        parentId: String? = inferParentId(id),
    ) {
        documents[id] = Doc(id, name, mime, size, flags, parentId)
    }

    /** Whether the provider still has a document, by id. */
    fun contains(documentId: String): Boolean = documentId in documents

    fun addMalformedRow(id: String) {
        documents[id] = Doc(id, "", "", null, null, inferParentId(id))
        malformed += id
    }

    private fun inferParentId(id: String): String? {
        val separator = id.lastIndexOf('/')
        return if (separator > 0) id.substring(0, separator) else null
    }

    fun callCount(method: String): Int = calls[method] ?: 0

    private fun bump(method: String) {
        calls[method] = (calls[method] ?: 0) + 1
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queryCount++
        if (throwSecurityOnQuery || queryCount == throwSecurityOnQueryNumber) {
            throw SecurityException("revoked")
        }
        if (throwOnQuery || queryCount == throwOnQueryNumber) {
            throw RuntimeException("provider crashed")
        }
        if (nullCursor || queryCount == nullCursorOnQueryNumber) return null

        val childrenParentId = childrenParentId(uri)
        if (childrenParentId != null) {
            val childCursor = MatrixCursor(TEST_COLUMNS)
            documents.values
                .filter { it.parentId == childrenParentId }
                .forEach { doc ->
                    childCursor.newRow().apply {
                        add(COLUMN_ID, doc.id)
                        add(COLUMN_NAME, if (doc.id in malformed) null else doc.name)
                        add(COLUMN_SIZE, observedSize(doc))
                        add(COLUMN_MIME, doc.mime)
                        add(COLUMN_FLAGS, doc.flags)
                    }
                }
            return childCursor
        }

        val id = DocumentsContract.getDocumentId(uri)
        val doc = documents[id] ?: return MatrixCursor(TEST_COLUMNS)

        val cursor = MatrixCursor(TEST_COLUMNS)
        cursor.newRow().apply {
            // Every column is written exactly once, in projection order.
            // Omitting one is how this double models a provider that declines
            // to answer, and a null value is how it models SQL NULL.
            //
            // [queryDocumentIdOverride] answers with a different identity than
            // the one requested, which is what a same-name replacement looks
            // like from the caller's side.
            add(
                COLUMN_ID,
                if (omitDocumentIdOnQuery) null else queryDocumentIdOverride ?: doc.id,
            )
            add(COLUMN_NAME, if (doc.id in malformed) null else doc.name)
            add(COLUMN_SIZE, observedSize(doc))
            add(COLUMN_MIME, doc.mime)
            add(COLUMN_FLAGS, doc.flags)
        }
        return cursor
    }

    private fun childrenParentId(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.lastOrNull() != "children") return null
        return segments.getOrNull(segments.lastIndex - 1)
    }

    private fun observedSize(document: Doc): Long? =
        contents[document.id]?.length() ?: document.size

    override fun getType(uri: Uri): String =
        documents[DocumentsContract.getDocumentId(uri)]?.mime ?: "vnd.android.cursor.item/vnd.android.document"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if ('w' in mode) writeOpenCount++
        if (throwSecurityOnOpen) throw SecurityException("revoked")
        failOpenWith?.let { throw it }
        if (nullOnOpen) return null
        val id = DocumentsContract.getDocumentId(uri)
        val file = contents.getOrPut(id) { File(storageDir, id.hashCode().toString()).apply { createNewFile() } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    /**
     * The document URI the platform handed us, under whichever key it used.
     *
     * DocumentsContract has written the target of a call() under both the
     * `uri` and `document_id` keys depending on the method and the release.
     * A fake that hardcoded one would quietly read null, then "succeed" at
     * nothing, and the test would fail for a reason that has nothing to do
     * with the gateway. Trying each documented key keeps the fake honest
     * about what it was actually asked to do.
     */
    @Suppress("DEPRECATION")
    private fun Bundle.uriOrNull(vararg keys: String): Uri? {
        for (key in keys) {
            val value = getParcelable<Uri>(key)
            if (value != null) return value
        }
        return null
    }

    /**
     * The result bundle for a call that produced a document.
     *
     * Written under both keys for the same reason as [uriOrNull]: whichever
     * one the platform reads, the identity it gets back is the one this
     * provider decided on.
     */
    private fun documentResult(id: String): Bundle = Bundle().apply {
        val uri = uriForId(id)
        putParcelable("uri", uri)
        putParcelable(DocumentsContract.Document.COLUMN_DOCUMENT_ID, uri)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        bump(method)
        if (throwSecurityOnCall) throw SecurityException("revoked")
        val bundle = extras ?: return null

        return when (method) {
            "android:createDocument" -> {
                if (nullOnCreate) return null
                val parent = bundle.uriOrNull("uri", DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: return null
                var name = bundle.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: return null
                if (renameOnCreate) name = name.replace(".mp4", " (1).mp4")
                val mime = bundle.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    ?: "video/mp4"
                val parentId = DocumentsContract.getDocumentId(parent)
                val id = "$parentId/$name"
                documents[id] = Doc(id, name, mime, 0L, 0, parentId)
                documentResult(id)
            }

            "android:renameDocument" -> {
                if (nullOnRename) return null
                val target = bundle.uriOrNull("uri", DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: return null
                val name = bundle.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: return null
                val oldId = DocumentsContract.getDocumentId(target)
                val doc = documents.remove(oldId) ?: return null
                val newId = "${oldId.substringBeforeLast('/')}/$name"
                documents[newId] = doc.copy(id = newId, name = name)
                contents.remove(oldId)?.let { contents[newId] = it }
                documentResult(newId)
            }

            "android:deleteDocument" -> {
                if (deleteBehaviour == DeleteBehaviour.THROWS_SECURITY) {
                    throw SecurityException("revoked")
                }
                if (deleteBehaviour == DeleteBehaviour.THROWS) {
                    throw RuntimeException("provider crashed")
                }
                val target = bundle.uriOrNull("uri", DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val id = target?.let { DocumentsContract.getDocumentId(it) }
                // Existence is checked without removing: what a provider
                // removes and what it reports are separate facts, and this
                // double has to be able to disagree about either.
                val existed = id != null && documents.containsKey(id)
                val removes = existed && deleteBehaviour.removes
                if (removes) {
                    documents.remove(id)
                    contents.remove(id)
                }
                val reported = if (deleteBehaviour.reportsSuccess) existed else false
                lastDeleteRemoved = reported
                Bundle().apply { putBoolean("result", reported) }
            }

            "android:isChildDocument" -> {
                val parent = bundle.uriOrNull("uri", DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: return Bundle().apply { putBoolean("result", false) }
                val child = bundle.uriOrNull("android.content.extra.TARGET_URI")
                    ?: return Bundle().apply { putBoolean("result", false) }
                val parentId = DocumentsContract.getDocumentId(parent)
                val childId = DocumentsContract.getDocumentId(child)
                Bundle().apply {
                    putBoolean("result", isSameOrDescendant(parentId, childId))
                }
            }

            "android:findDocumentPath" -> Bundle()

            else -> null
        }
    }

    private fun isSameOrDescendant(parentId: String, childId: String): Boolean {
        if (parentId == childId) return true
        var cursor = documents[childId]
        val visited = HashSet<String>()
        while (cursor != null && visited.add(cursor.id)) {
            val nextParentId = cursor.parentId ?: return false
            if (nextParentId == parentId) return true
            cursor = documents[nextParentId]
        }
        return false
    }

    private fun uriForId(id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeBaseUri, id)
}
