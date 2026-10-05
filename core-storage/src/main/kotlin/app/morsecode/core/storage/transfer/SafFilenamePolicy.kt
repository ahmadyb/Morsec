package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.ProtocolLimits
import java.lang.Character
import java.nio.charset.StandardCharsets

/**
 * Filename rules shared by every name sent to a SAF document provider.
 *
 * A transfer path segment is the nearest authoritative storage bound for one
 * provider filename, so the SAF budget is [ProtocolLimits.MAX_PATH_SEGMENT_BYTES]
 * UTF-8 bytes. Unicode normalization deliberately preserves the caller's exact
 * well-formed code-point sequence, matching `RelativeTransferPath`; equivalent
 * NFC/NFD spellings are not silently renamed. Byte accounting is always over
 * UTF-8, never Kotlin `String.length`.
 */
public object SafFilenamePolicy {

    public const val MAX_FILENAME_BYTES: Int = ProtocolLimits.MAX_PATH_SEGMENT_BYTES

    /** Validate and return an original/final filename without changing spelling. */
    public fun requireOriginalName(raw: String): String {
        requireWellFormedUnicode(raw)
        requireSafeFilename(raw)
        require(utf8Length(raw) <= MAX_FILENAME_BYTES) {
            "SAF filename exceeds $MAX_FILENAME_BYTES UTF-8 bytes"
        }
        return raw
    }

    /** Number of bytes the well-formed [name] occupies in UTF-8. */
    public fun utf8Length(name: String): Int {
        requireWellFormedUnicode(name)
        return name.toByteArray(StandardCharsets.UTF_8).size
    }

    /** Insert a bounded duplicate marker before the extension. */
    public fun withDuplicateSuffix(name: String, suffix: String): String {
        val original = requireOriginalName(name)
        requireSafeSuffix(suffix)
        val (base, extension) = splitExtension(original)
        val retainedBase = fitBase(base, extension + suffix)
        return (retainedBase + suffix + extension).also(::requireGeneratedName)
    }

    /** Append a deterministic temporary/backup marker after the original extension. */
    public fun withAppendedSuffix(name: String, suffix: String): String {
        val original = requireOriginalName(name)
        requireSafeSuffix(suffix)
        val (base, extension) = splitExtension(original)
        val retainedBase = fitBase(base, extension + suffix)
        return (retainedBase + extension + suffix).also(::requireGeneratedName)
    }

    private fun requireGeneratedName(name: String) {
        requireWellFormedUnicode(name)
        requireSafeFilename(name)
        check(utf8Length(name) <= MAX_FILENAME_BYTES) {
            "generated SAF filename exceeds $MAX_FILENAME_BYTES UTF-8 bytes"
        }
    }

    private fun requireSafeFilename(name: String) {
        require(name.isNotEmpty() && !name.isBlank()) { "SAF filename must not be empty" }
        require(name != "." && name != "..") { "SAF filename must not be a traversal marker" }
        require(name.none { it == '\u0000' || Character.isISOControl(it) }) {
            "SAF filename must not contain control characters"
        }
        require('/' !in name && '\\' !in name) { "SAF filename must not contain a path separator" }
    }

    private fun requireSafeSuffix(suffix: String) {
        requireWellFormedUnicode(suffix)
        require(suffix.isNotEmpty()) { "SAF filename suffix must not be empty" }
        require(suffix.none {
            it == '\u0000' || Character.isISOControl(it) || it == '/' || it == '\\'
        }) {
            "SAF filename suffix contains a forbidden character"
        }
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        // A leading dot names a hidden-style file, not an empty basename plus
        // extension. The whole name is therefore retained as the basename.
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /**
     * Keep at least one complete Unicode code point in the basename while
     * reserving the exact UTF-8 budget for [reservedSuffix].
     */
    private fun fitBase(base: String, reservedSuffix: String): String {
        val reservedBytes = utf8Length(reservedSuffix)
        val budget = MAX_FILENAME_BYTES - reservedBytes
        require(budget > 0) { "SAF filename suffix cannot fit" }

        val kept = StringBuilder()
        var index = 0
        var used = 0
        while (index < base.length) {
            val first = base[index]
            val width = if (first.isHighSurrogate()) 2 else 1
            val codePoint = base.substring(index, index + width)
            val bytes = utf8Length(codePoint)
            if (used + bytes > budget) break
            kept.append(codePoint)
            used += bytes
            index += width
        }
        require(kept.isNotEmpty()) {
            "SAF extension and required suffix leave no filename basename"
        }
        return kept.toString()
    }

    private fun requireWellFormedUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "SAF filename must contain well-formed Unicode"
                    }
                    index += 2
                }

                char.isLowSurrogate() -> throw IllegalArgumentException(
                    "SAF filename must contain well-formed Unicode",
                )

                else -> index++
            }
        }
    }
}
