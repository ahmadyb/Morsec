package app.morsecode.core.storage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.SafGrant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage Access Framework access.
 *
 * The user picks a folder once (the Files tab's folder rows and the receive
 * target both come from here), the app takes a persistable permission, and every
 * later read goes through [DocumentFile] — which works identically on API 23 and
 * on API 36, so no version-specific file code is needed for granted folders.
 */
@Singleton
internal class SafTreeReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True while the platform still honours this tree grant. */
    public fun isValid(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return false
        return tree.exists() && tree.canRead()
    }

    public fun displayName(uri: Uri): String? {
        val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        return tree?.name?.takeIf { it.isNotBlank() } ?: lastSegment(uri)
    }

    public fun children(treeUri: String): List<MediaItem> {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return emptyList()
        val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return emptyList()
        return runCatching { tree.listFiles() }.getOrDefault(emptyArray()).mapNotNull { file -> toItem(file) }
    }

    /** The folder row itself, shown above its children and on the Files tab. */
    public fun folderItem(grant: SafGrant, childCount: Int = 0, sizeBytes: Long = 0L): MediaItem = MediaItem(
        id = "saf:${grant.id}",
        displayName = grant.displayName,
        kind = MediaKind.FOLDER,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        sizeBytes = sizeBytes,
        dateModifiedEpochMillis = grant.grantedEpochMillis,
        uriString = grant.treeUri,
        bucket = grant.displayName,
        splitCount = childCount,
        isFolder = true,
    )

    public fun byUri(documentUri: String): MediaItem? {
        val uri = runCatching { Uri.parse(documentUri) }.getOrNull() ?: return null
        val file = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            ?: runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return null
        return toItem(file)
    }

    private fun toItem(file: DocumentFile): MediaItem? {
        val name = file.name ?: return null
        val isFolder = file.isDirectory
        return MediaItem(
            id = "saf:${runCatching { DocumentsContract.getDocumentId(file.uri) }.getOrDefault(file.uri.toString())}",
            displayName = name,
            kind = if (isFolder) MediaKind.FOLDER else MediaKind.of(name, file.type),
            mimeType = if (isFolder) DocumentsContract.Document.MIME_TYPE_DIR else file.type,
            sizeBytes = if (isFolder) 0L else file.length(),
            dateModifiedEpochMillis = file.lastModified(),
            uriString = file.uri.toString(),
            isFolder = isFolder,
        )
    }

    /** Takes a persistable read/write permission; false when the picker denied it. */
    public fun takePersistablePermission(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
    }

    public fun releasePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun lastSegment(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
}
