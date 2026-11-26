package app.morsecode.core.storage.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controlled provider in the library's self-instrumenting test APK only.
 * The resolver, descriptors, files and pipes are Android framework objects; the
 * provider's metadata and bounded faults are deliberately test-controlled.
 */
public class Slice4sTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: Slice4sProviderControls.columns
        val row = Slice4sProviderControls.row
        return MatrixCursor(columns).apply {
            if (row != null && uri.lastPathSegment == row.documentId) {
                addRow(columns.map<String, Any?> { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.documentId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "fixture.bin"
                        DocumentsContract.Document.COLUMN_SIZE -> row.reportedSize
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                        else -> null
                    }
                }.toTypedArray())
            }
        }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = Slice4sProviderControls.row ?: throw FileNotFoundException("test document absent")
        if (uri.lastPathSegment != row.documentId) throw FileNotFoundException("test document absent")
        if (mode.contains('w')) {
            Slice4sProviderControls.writeAttempts.incrementAndGet()
            if (Slice4sProviderControls.denyWrites) throw SecurityException("slice4s private provider detail")
            return Slice4sProviderControls.track(
                ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE),
            )
        }
        Slice4sProviderControls.readAttempts.incrementAndGet()
        if (Slice4sProviderControls.denyReads) throw SecurityException("slice4s private provider detail")
        if (!row.pipe) {
            return Slice4sProviderControls.track(ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        val (reader, writer) = ParcelFileDescriptor.createReliablePipe()
        Slice4sProviderControls.track(reader)
        Slice4sProviderControls.track(writer)
        val thread = Thread({
            val output = FileOutputStream(writer.fileDescriptor)
            try {
                val bytes = row.file.readBytes()
                val count = row.failAfter?.coerceIn(0, bytes.size) ?: bytes.size
                output.write(bytes, 0, count)
                output.flush()
                if (row.failAfter != null) writer.closeWithError("slice4s private provider detail") else writer.close()
            } catch (_: Exception) {
                // The capability probe deliberately closes a pipe without consuming it.
                // EPIPE in that writer is expected and is not reported as a test pass.
                runCatching { writer.closeWithError("slice4s writer stopped") }
            } finally {
                runCatching { output.close() }
            }
        }, "slice4s-provider-writer").apply { isDaemon = true }
        Slice4sProviderControls.track(thread)
        thread.start()
        return reader
    }
}

/** No production source or manifest may reference these fault controls. */
internal object Slice4sProviderControls {
    const val AUTHORITY = "app.morsecode.core.storage.test.slice4s.provider"
    const val ROOT_ID = "primary:Slice4S"
    const val DOCUMENT_ID = "primary:Slice4S/fixture.bin"
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    data class Row(
        val documentId: String,
        val file: File,
        val pipe: Boolean,
        @Volatile var reportedSize: Long?,
        val failAfter: Int?,
    )

    @Volatile var row: Row? = null
    @Volatile var denyReads: Boolean = false
    @Volatile var denyWrites: Boolean = false
    val readAttempts = AtomicInteger()
    val writeAttempts = AtomicInteger()
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private val threads = mutableListOf<Thread>()
    private val lock = Any()

    fun seed(context: Context, bytes: ByteArray, pipe: Boolean, reportedSize: Long?, failAfter: Int? = null): Uri {
        val directory = File(context.cacheDir, "slice4s-test-provider")
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, "document.bin").apply { writeBytes(bytes) }
        row = Row(DOCUMENT_ID, file, pipe, reportedSize, failAfter)
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DOCUMENT_ID)
    }

    fun track(descriptor: ParcelFileDescriptor): ParcelFileDescriptor {
        synchronized(lock) { descriptors += descriptor }
        return descriptor
    }

    fun track(thread: Thread) {
        synchronized(lock) { threads += thread }
    }

    /** Close every remaining endpoint, wait for all writers, and remove the test documents. */
    fun cleanup(context: Context) {
        val ownedDescriptors: List<ParcelFileDescriptor>
        val ownedThreads: List<Thread>
        synchronized(lock) {
            ownedDescriptors = descriptors.toList()
            ownedThreads = threads.toList()
            descriptors.clear()
            threads.clear()
        }
        ownedDescriptors.forEach { runCatching { it.close() } }
        ownedThreads.forEach { it.join(3_000) }
        check(ownedThreads.none { it.isAlive }) { "test pipe writer did not stop" }
        row = null
        denyReads = false
        denyWrites = false
        readAttempts.set(0)
        writeAttempts.set(0)
        val directory = File(context.cacheDir, "slice4s-test-provider")
        check(!directory.exists() || directory.deleteRecursively()) { "test documents were not removed" }
    }
}
