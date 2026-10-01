package app.morsecode.core.storage.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore queries behind the Photos, Videos, Music and (on API ≤ 32) Files
 * tabs.
 *
 * Every column read is guarded: OEM builds have been known to omit optional
 * columns, and a missing column must produce an item with a zero value rather
 * than a crash inside the grid (master prompt §9).
 */
@Singleton
internal class MediaStoreReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStoreDataSource {

    public override fun images(): List<MediaItem> = read(
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
        ),
        kind = MediaKind.IMAGE,
    ) { cursor ->
        widthPixels = cursor.intOf(MediaStore.MediaColumns.WIDTH)
        heightPixels = cursor.intOf(MediaStore.MediaColumns.HEIGHT)
        bucket = cursor.stringOf(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
    }

    public override fun videos(): List<MediaItem> = read(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME,
        ),
        kind = MediaKind.VIDEO,
    ) { cursor ->
        widthPixels = cursor.intOf(MediaStore.MediaColumns.WIDTH)
        heightPixels = cursor.intOf(MediaStore.MediaColumns.HEIGHT)
        durationMillis = cursor.longOf(MediaStore.MediaColumns.DURATION)
        bucket = cursor.stringOf(MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME)
    }

    public override fun audio(): List<MediaItem> = read(
        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.ARTIST,
        ),
        kind = MediaKind.AUDIO,
    ) { cursor ->
        durationMillis = cursor.longOf(MediaStore.MediaColumns.DURATION)
        title = cursor.stringOf(MediaStore.Audio.AudioColumns.TITLE)
        album = cursor.stringOf(MediaStore.Audio.AudioColumns.ALBUM)
        artist = cursor.stringOf(MediaStore.Audio.AudioColumns.ARTIST)
        bucket = album
    }

    /**
     * Non-media files: documents, archives, APKs sitting in shared storage.
     *
     * Only used up to API 32. From API 33 the platform stops exposing arbitrary
     * shared documents to a media-read permission, and [observeDocuments] serves
     * the user's SAF folders instead.
     */
    public override fun documents(): List<MediaItem> {
        if (Build.VERSION.SDK_INT >= 33) return emptyList()
        // RELATIVE_PATH only exists from API 29; older levels expose DATA.
        val usesRelativePath = Build.VERSION.SDK_INT >= 29
        @Suppress("DEPRECATION")
        val folderColumn = if (usesRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
            "application/%",
            "text/%",
        )
        return read(
            collection = MediaStore.Files.getContentUri("external"),
            projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                folderColumn,
            ),
            kind = MediaKind.OTHER,
            selection = selection,
            selectionArgs = args,
        ) { cursor ->
            val name = cursor.stringOf(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            val mime = cursor.stringOf(MediaStore.MediaColumns.MIME_TYPE)
            kind = MediaKind.of(name, mime)
            bucket = if (usesRelativePath) relativeParent(cursor.stringOf(folderColumn))
            else parentOfAbsolutePath(cursor.stringOf(folderColumn))
        }
    }

    /** Resolves one item by its opaque id (`image:123`, `audio:45`, …). */
    public override fun byId(id: String): MediaItem? {
        val prefix = id.substringBefore(':', "")
        val rowId = id.substringAfter(':', "").toLongOrNull() ?: return null
        val collection = when (prefix) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return read(
            collection = collection,
            projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
            ),
            kind = MediaKind.OTHER,
            selection = "${MediaStore.MediaColumns._ID} = ?",
            selectionArgs = arrayOf(rowId.toString()),
        ) { cursor ->
            val name = cursor.stringOf(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            kind = MediaKind.of(name, cursor.stringOf(MediaStore.MediaColumns.MIME_TYPE))
        }.firstOrNull()
    }

    private inline fun read(
        collection: Uri,
        projection: Array<String>,
        kind: MediaKind,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        extra: MediaItemBuilder.(Cursor) -> Unit = {},
    ): List<MediaItem> {
        val cursor = runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)
        }.getOrNull() ?: return emptyList()
        return cursor.use { active ->
            val out = mutableListOf<MediaItem>()
            while (active.moveToNext()) {
                val id = active.longOf(MediaStore.MediaColumns._ID)
                val name = active.stringOf(MediaStore.MediaColumns.DISPLAY_NAME)
                if (id == 0L && name.isNullOrBlank()) continue
                val builder = MediaItemBuilder().apply {
                    idPrefix = prefixFor(collection)
                    rowId = id
                    displayName = name ?: "unknown"
                    defaultKind = kind
                    sizeBytes = active.longOf(MediaStore.MediaColumns.SIZE)
                    dateModifiedEpochMillis = active.longOf(MediaStore.MediaColumns.DATE_MODIFIED) * 1_000L
                    mimeType = active.stringOf(MediaStore.MediaColumns.MIME_TYPE)
                    uriString = ContentUris.withAppendedId(collection, id).toString()
                    extra(active)
                }
                out += builder.build()
            }
            out
        }
    }

    private fun prefixFor(collection: Uri): String {
        val asString = collection.toString()
        return when (asString) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() -> "image"
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString() -> "video"
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString() -> "audio"
            else -> "file"
        }
    }

    private fun relativeParent(relativePath: String?): String? =
        relativePath?.trim('/')?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

    /** "/storage/emulated/0/Download/x.pdf" -> "Download". */
    private fun parentOfAbsolutePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val parent = path.substringBeforeLast('/', "")
        return parent.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }
}

/** Accumulates one cursor row into a [MediaItem]. */
internal class MediaItemBuilder {
    var idPrefix: String = "file"
    var rowId: Long = 0L
    var displayName: String = "unknown"
    var defaultKind: MediaKind = MediaKind.OTHER
    var kind: MediaKind? = null
    var sizeBytes: Long = 0L
    var dateModifiedEpochMillis: Long = 0L
    var mimeType: String? = null
    var uriString: String? = null
    var durationMillis: Long = 0L
    var widthPixels: Int = 0
    var heightPixels: Int = 0
    var title: String? = null
    var album: String? = null
    var artist: String? = null
    var bucket: String? = null

    fun build(): MediaItem = MediaItem(
        id = "$idPrefix:$rowId",
        displayName = displayName,
        kind = kind ?: MediaKind.of(displayName, mimeType).takeIf { defaultKind == MediaKind.OTHER } ?: defaultKind,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateModifiedEpochMillis = dateModifiedEpochMillis,
        uriString = uriString,
        durationMillis = durationMillis,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        album = album,
        artist = artist,
        title = title,
        bucket = bucket,
    )
}

internal fun Cursor.stringOf(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else runCatching { getString(index) }.getOrNull()
}

internal fun Cursor.longOf(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else runCatching { getLong(index) }.getOrDefault(0L)
}

internal fun Cursor.intOf(column: String): Int {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0 else runCatching { getInt(index) }.getOrDefault(0)
}
