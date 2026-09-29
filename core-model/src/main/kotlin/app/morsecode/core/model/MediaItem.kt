package app.morsecode.core.model

/**
 * The five Files destination categories, in the approved order. The `id` values
 * are the mockup's own tab keys, which keeps the parity harness honest: the
 * Kotlin enum is compared against `const tabs=['photos','videos','music','apps','files']`.
 */
public enum class FileCategory(public val id: String) {
    PHOTOS("photos"),
    VIDEOS("videos"),
    MUSIC("music"),
    APPS("apps"),
    FILES("files"),
    ;

    public companion object {
        public val ordered: List<FileCategory> = entries.toList()
        public fun fromId(id: String?): FileCategory? = entries.firstOrNull { it.id == id }
        public fun indexOf(category: FileCategory): Int = ordered.indexOf(category)
    }
}

/** File kind, driving the tile icon and its accent colour. */
public enum class MediaKind(public val id: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    DOC("doc"),
    ZIP("zip"),
    APK("apk"),
    FOLDER("folder"),
    OTHER("other"),
    ;

    public companion object {
        /** Parses a persisted or wire kind id; unknown values become [OTHER]. */
        public fun fromId(id: String?): MediaKind = entries.firstOrNull { it.id == id } ?: OTHER

        /**
         * Maps a MIME type to a kind. Deliberately tolerant: malformed or absent
         * metadata must never crash the grid (master prompt §9).
         */
        public fun fromMimeType(mime: String?): MediaKind {
            val type = mime?.lowercase()?.trim().orEmpty()
            if (type.isEmpty()) return OTHER
            return when {
                type.startsWith("image/") -> IMAGE
                type.startsWith("video/") -> VIDEO
                type.startsWith("audio/") -> AUDIO
                type == "application/vnd.android.package-archive" -> APK
                type.contains("zip") || type.contains("compressed") || type.contains("x-7z") ||
                    type.contains("x-rar") || type.contains("x-tar") || type.contains("gzip") -> ZIP
                type.startsWith("text/") || type.contains("pdf") || type.contains("word") ||
                    type.contains("sheet") || type.contains("document") || type.contains("powerpoint") ||
                    type.contains("epub") -> DOC
                else -> OTHER
            }
        }

        /** Maps a file extension when no MIME type is available. */
        public fun fromExtension(name: String): MediaKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> IMAGE
                "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts" -> VIDEO
                "mp3", "flac", "aac", "m4a", "ogg", "opus", "wav", "wma" -> AUDIO
                "apk", "apks", "xapk", "apkm" -> APK
                "zip", "rar", "7z", "tar", "gz", "bz2" -> ZIP
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md", "epub" -> DOC
                else -> OTHER
            }
        }

        public fun of(name: String, mime: String?): MediaKind {
            val byMime = fromMimeType(mime)
            return if (byMime == OTHER) fromExtension(name) else byMime
        }
    }
}

/**
 * One row in a media list. [id] is a stable opaque identifier: a MediaStore id,
 * an SAF document id or an app package name — never a raw filesystem path shown
 * to the user or to a browser.
 */
public data class MediaItem(
    val id: String,
    val displayName: String,
    val kind: MediaKind,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateModifiedEpochMillis: Long,
    /** Uri string (content:// or file://) resolved by the storage layer. */
    val uriString: String? = null,
    val durationMillis: Long = 0L,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
    val album: String? = null,
    val artist: String? = null,
    val title: String? = null,
    /** Grouping key for day headers, e.g. an SAF folder or a package name. */
    val bucket: String? = null,
    /** Split APK members when this item is a split package. */
    val splitCount: Int = 0,
    val isFolder: Boolean = false,
) {
    val isImage: Boolean get() = kind == MediaKind.IMAGE
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
    val isAudio: Boolean get() = kind == MediaKind.AUDIO
}

/** Sort keys offered by the sort sheet, in the mockup's order. */
public enum class SortKey(public val id: String) {
    DATE("date"),
    NAME("name"),
    SIZE("size"),
    TYPE("type"),
    ;

    public companion object {
        public val ordered: List<SortKey> = entries.toList()
        public fun fromId(id: String?): SortKey = entries.firstOrNull { it.id == id } ?: DATE
    }
}

public enum class SortDirection(public val id: String) {
    ASC("asc"),
    DESC("desc"),
    ;

    public companion object {
        public fun fromId(id: String?): SortDirection = entries.firstOrNull { it.id == id } ?: DESC
    }
}

public data class SortOrder(val key: SortKey = SortKey.DATE, val direction: SortDirection = SortDirection.DESC) {
    public fun toggleDirection(): SortOrder =
        copy(direction = if (direction == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC)
}

/**
 * Sorts any list with the media comparator used across the phone UI.
 *
 * Folders always come first, exactly as the mockup does, so a folder browser
 * never buries a directory under a thousand files.
 */
public fun <T> List<T>.applySortOrder(order: SortOrder, selector: (T) -> MediaItem): List<T> {
    val comparator = Comparator<T> { a, b ->
        val left = selector(a)
        val right = selector(b)
        if (left.isFolder != right.isFolder) {
            return@Comparator if (left.isFolder) -1 else 1
        }
        when (order.key) {
            SortKey.NAME -> left.displayName.compareTo(right.displayName, ignoreCase = true)
            SortKey.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
            SortKey.TYPE -> left.kind.id.compareTo(right.kind.id)
                .takeIf { it != 0 }
                ?: left.displayName.compareTo(right.displayName, ignoreCase = true)
            SortKey.DATE -> left.dateModifiedEpochMillis.compareTo(right.dateModifiedEpochMillis)
        }
    }
    val directed = if (order.direction == SortDirection.ASC) comparator else comparator.reversed()
    // Re-apply the folder-first rule after reversing so it survives DESC order.
    return this.sortedWith(Comparator { a, b ->
        val left = selector(a)
        val right = selector(b)
        if (left.isFolder != right.isFolder) {
            if (left.isFolder) -1 else 1
        } else {
            directed.compare(a, b)
        }
    })
}
