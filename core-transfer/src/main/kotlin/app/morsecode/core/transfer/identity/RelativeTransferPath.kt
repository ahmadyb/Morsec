package app.morsecode.core.transfer.identity

import app.morsecode.core.transfer.ProtocolLimits

/*
 * A safe, relative path inside one transfer.
 *
 * The path is the only part of a descriptor that a peer fully controls and that
 * could plausibly be handed to a file API, so it is validated harder than
 * anything else in the module. Rejected inputs include absolute paths, drive
 * letters, `..` traversal in any of its three common spellings (plain,
 * percent-encoded, back-slashed), empty segments, NUL and control bytes, and
 * anything over the documented byte ceilings.
 *
 * What is deliberately absent: an absolute path type. The core never needs one,
 * and not having one makes it impossible to leak a private device path through a
 * protocol model.
 */

private val SEGMENT_CONTROL = Regex("""\p{Cntrl}""")
private val DRIVE_LETTER = Regex("""^[A-Za-z]:""")
private val PERCENT_ESCAPE = Regex("""%([0-9A-Fa-f]{2})""")
private val ALL_DOTS = Regex("""^\.+$""")

/**
 * Decodes percent escapes so `%2e%2e` and `..` are judged by the same rule.
 * Returns null when the escape sequence is malformed, which is itself a reason
 * to reject: a path that cannot be decoded unambiguously cannot be trusted.
 */
internal fun percentDecodeOrNull(segment: String): String? {
    if ('%' !in segment) return segment
    val out = StringBuilder(segment.length)
    var index = 0
    while (index < segment.length) {
        val char = segment[index]
        if (char == '%') {
            if (index + 2 >= segment.length) return null
            val hex = segment.substring(index + 1, index + 3)
            val value = hex.toIntOrNull(16) ?: return null
            out.append(value.toChar())
            index += 3
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

internal fun requireValidRelativePath(raw: String) {
    val encoded = raw.toByteArray(Charsets.UTF_8)
    require(encoded.isNotEmpty()) { "relativePath must not be empty" }
    require(encoded.size <= ProtocolLimits.MAX_RELATIVE_PATH_BYTES) {
        "relativePath must not exceed ${ProtocolLimits.MAX_RELATIVE_PATH_BYTES} UTF-8 bytes"
    }
    require(!SEGMENT_CONTROL.containsMatchIn(raw)) { "relativePath must not contain control characters" }
    require(!raw.startsWith("/") && !raw.startsWith("\\")) { "relativePath must not be absolute" }
    require(!DRIVE_LETTER.containsMatchIn(raw)) { "relativePath must not contain a drive letter" }
    require(!raw.endsWith("/") && !raw.endsWith("\\")) { "relativePath must not end with a separator" }

    val segments = raw.replace('\\', '/').split('/')
    for (segment in segments) {
        require(segment.isNotEmpty()) { "relativePath must not contain an empty segment" }
        require(segment != "." && segment != "..") { "relativePath must not contain a traversal segment" }
        require(!ALL_DOTS.matches(segment)) { "relativePath must not contain a dot-only segment" }
        val segmentBytes = segment.toByteArray(Charsets.UTF_8)
        require(segmentBytes.size <= ProtocolLimits.MAX_PATH_SEGMENT_BYTES) {
            "a relativePath segment must not exceed ${ProtocolLimits.MAX_PATH_SEGMENT_BYTES} UTF-8 bytes"
        }
        val decoded = percentDecodeOrNull(segment)
            ?: throw IllegalArgumentException("relativePath contains a malformed percent escape")
        require(!decoded.contains('/') && !decoded.contains('\\')) {
            "relativePath must not contain an encoded path separator"
        }
        require(decoded != "." && decoded != "..") {
            "relativePath must not contain an encoded traversal segment"
        }
    }
}

internal fun isValidRelativePath(raw: String): Boolean = try {
    requireValidRelativePath(raw)
    true
} catch (e: IllegalArgumentException) {
    false
}

/**
 * A validated, relative, peer-supplied path.
 *
 * Always uses `/` as the separator regardless of what the peer sent; the
 * storage adapter that eventually creates the file is responsible for mapping
 * it onto the platform's separator.
 */
public data class RelativeTransferPath(public val value: String) {
    init {
        requireValidRelativePath(value)
    }

    /** Individual path components, always non-empty and never `.` or `..`. */
    public val segments: List<String> = value.replace('\\', '/').split('/')

    /** Depth of the path: 1 for a single file at the batch root. */
    public val depth: Int get() = segments.size

    /** The final component: the file name for a file, the folder name for a folder. */
    public val lastSegment: String get() = segments.last()

    /** Everything before [lastSegment], or null when the path is one level deep. */
    public val parent: RelativeTransferPath?
        get() = if (segments.size <= 1) {
            null
        } else {
            RelativeTransferPath(segments.dropLast(1).joinToString("/"))
        }

    override fun toString(): String = value

    public companion object {
        public fun isValid(raw: String): Boolean = isValidRelativePath(raw)
        public fun orNull(raw: String): RelativeTransferPath? =
            if (isValid(raw)) RelativeTransferPath(raw) else null
    }
}
