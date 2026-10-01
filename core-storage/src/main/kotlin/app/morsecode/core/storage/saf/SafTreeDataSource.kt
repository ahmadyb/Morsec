package app.morsecode.core.storage.saf

import android.net.Uri
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.SafGrant

/** The SAF operations consumed by [app.morsecode.core.storage.DefaultMediaRepository]. */
internal interface SafTreeDataSource {
    fun isValid(treeUri: String): Boolean

    fun displayName(uri: Uri): String?

    /** Direct children, preserving the folder hierarchy for the Files browser. */
    fun children(treeUri: String): List<MediaItem>

    /**
     * All descendants of a granted folder, including folders and files at any depth.
     *
     * The media categories use this index so an image or track inside a subfolder is
     * visible to the same repository flows as MediaStore content. The folder browser
     * continues to use [children] and therefore remains hierarchical.
     */
    fun descendants(treeUri: String): List<MediaItem> =
        SafTreeWalker.descendants(treeUri) { parentUri -> children(parentUri) }

    fun folderItem(grant: SafGrant, childCount: Int = 0, sizeBytes: Long = 0L): MediaItem

    fun byUri(documentUri: String): MediaItem?

    fun takePersistablePermission(uri: Uri): Boolean

    fun releasePersistablePermission(uri: Uri)
}
