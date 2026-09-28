package app.morsecode.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a text export into the app's cache directory and hands a content uri to
 * the chooser.
 *
 * `file://` uris are rejected from API 24 onwards, so the FileProvider declared
 * in the manifest is the only way these exports leave the app — and the uri
 * permission is granted to the receiving app alone, not to the world.
 */
public object Exports {

    private const val DIRECTORY = "exports"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** @return the content uri of the written file, or null when writing failed. */
    public fun writeText(context: Context, fileName: String, text: String): Uri? = runCatching {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // Remove previous exports of the same name so the cache does not grow.
        val target = File(directory, fileName)
        if (target.exists()) target.delete()
        target.writeText(text)
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, target)
    }.getOrNull()

    public fun shareUri(context: Context, uri: Uri, subject: String, mimeType: String = "text/plain") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** True when some installed app can handle the uri (used to disable actions). */
    public fun canShare(context: Context, uri: Uri, mimeType: String = "text/plain"): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        return intent.resolveActivity(context.packageManager) != null
    }
}
