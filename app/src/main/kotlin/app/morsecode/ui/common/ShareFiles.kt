package app.morsecode.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.morsecode.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sharing selected files with the rest of the phone.
 *
 * `content://` uris from MediaStore and SAF are handed over directly with a read
 * grant. APKs live in `/data/app`, which a FileProvider path cannot expose, so
 * they are staged into the app's own share folder first — a real copy the
 * receiving app can read, not a link that would fail on the other side.
 */
public object ShareFiles {

    private const val SHARE_DIRECTORY = "shares"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** @return uris ready for an ACTION_SEND / ACTION_SEND_MULTIPLE intent. */
    public suspend fun prepare(context: Context, items: List<MediaItem>): List<Uri> =
        withContext(Dispatchers.IO) {
            items.mapNotNull { item ->
                val uri = item.uriString?.let { runCatching { it.toUri() }.getOrNull() }
                    ?: return@mapNotNull null
                if (uri.scheme == "file") stageFile(context, uri) else uri
            }
        }

    private fun stageFile(context: Context, fileUri: Uri): Uri? = runCatching {
        val path = fileUri.path ?: return null
        val source = File(path)
        if (!source.canRead()) return null
        val directory = File(context.filesDir, SHARE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, source.name)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, target)
    }.getOrNull()

    public fun share(context: Context, uris: List<Uri>, subject: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Opens one item with whatever app on the device can handle it. */
    public fun open(context: Context, item: MediaItem): Boolean {
        val uri = item.uriString?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Hands one image to an external editor, which is what §4.5 asks for: editing
     * through a compatible editor intent *where available*.
     *
     * The intent is started rather than resolved first, because from API 30 an app
     * cannot see the editors on the device without declaring queries for them. A
     * start that throws says the same thing a resolve would: nothing here can edit
     * this file, and the caller says so instead of pretending otherwise.
     */
    public fun edit(context: Context, item: MediaItem): Boolean {
        val uri = item.uriString?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, item.mimeType ?: "image/*")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
