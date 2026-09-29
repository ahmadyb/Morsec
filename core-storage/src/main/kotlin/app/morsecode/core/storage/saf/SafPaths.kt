package app.morsecode.core.storage.saf

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One level of a SAF folder's breadcrumb.
 *
 * SAF document ids are already paths (`primary:Download/2026`), so the levels a
 * browser shows are derived from the real id rather than from a name the app
 * invented — tapping one can therefore always be turned back into a uri.
 */
public data class FolderLevel(
    /** The folder's own name; for the volume level this is the volume id. */
    public val name: String,
    /** Document id of this level, ready for [SafPaths.uriFor]. */
    public val documentId: String,
    /** True for the volume level (`primary:`), which the UI names locally. */
    public val isVolume: Boolean = false,
)

/** What a typed or pasted path turned out to be. */
public sealed interface PathResolution {
    /** A document id inside the granted tree; safe to open. */
    public data class Inside(public val documentId: String) : PathResolution

    /** A well-formed path that is not inside the grant, so it stays closed. */
    public data object Outside : PathResolution

    /** Not a path this app can interpret (blank, `..`, or unparseable). */
    public data object Malformed : PathResolution
}

/**
 * SAF uri and document-id arithmetic for the internal folder browser.
 *
 * Deliberately pure string work with no `android.net` dependency: the rules are
 * the platform's (`content://authority/tree/ROOT` and
 * `content://authority/tree/ROOT/document/DOC`, percent-encoded segments), and
 * keeping them here means the breadcrumb, the descent into a subfolder and the
 * validation of a pasted path are all ordinary JVM-testable functions.
 */
public object SafPaths {

    private const val SCHEME = "content://"
    private const val TREE = "tree"
    private const val DOCUMENT = "document"
    private const val PRIMARY = "primary"

    /** The emulated primary volume's filesystem path, if a user pastes one. */
    private val EMULATED_ROOT = listOf("storage", "emulated", "0")

    private data class Parsed(val base: String, val segments: List<String>)

    /**
     * The document a browser uri points at: `tree/ROOT/document/DOC` yields DOC,
     * a bare `tree/ROOT` yields ROOT (the grant itself).
     */
    public fun documentIdOf(uriString: String): String? {
        val segments = parse(uriString)?.segments ?: return null
        val tree = segments.indexOf(TREE)
        if (tree < 0) return null
        val root = segments.getOrNull(tree + 1) ?: return null
        return if (segments.getOrNull(tree + 2) == DOCUMENT) {
            segments.getOrNull(tree + 3) ?: root
        } else {
            root
        }
    }

    /** The granted tree's own document id — the ceiling for path editing. */
    public fun rootDocumentIdOf(uriString: String): String? {
        val segments = parse(uriString)?.segments ?: return null
        val tree = segments.indexOf(TREE)
        if (tree < 0) return null
        return segments.getOrNull(tree + 1)
    }

    /** True when [uriString] is a SAF tree uri this app can list. */
    public fun isTreeUri(uriString: String): Boolean =
        uriString.startsWith(SCHEME) && rootDocumentIdOf(uriString) != null

    /**
     * The browser uri for [documentId] inside the tree [treeUri], or null when
     * either side is not a SAF tree uri. The result keeps the grant's authority
     * and root, so it can never point outside what the user granted.
     */
    public fun uriFor(treeUri: String, documentId: String): String? {
        val parsed = parse(treeUri) ?: return null
        val tree = parsed.segments.indexOf(TREE)
        if (tree < 0) return null
        val root = parsed.segments.getOrNull(tree + 1) ?: return null
        if (documentId.isBlank()) return null
        return "${parsed.base}/$TREE/${encode(root)}/$DOCUMENT/${encode(documentId)}"
    }

    /** Breadcrumb levels for a document id, volume first. */
    public fun levelsOf(documentId: String): List<FolderLevel> {
        if (documentId.isBlank()) return emptyList()
        val colon = documentId.indexOf(':')
        val volume = if (colon >= 0) documentId.substring(0, colon) else ""
        val path = if (colon >= 0) documentId.substring(colon + 1) else documentId
        val prefix = if (colon >= 0) "$volume:" else ""
        val levels = mutableListOf<FolderLevel>()
        if (volume.isNotEmpty()) {
            levels += FolderLevel(name = volume, documentId = prefix, isVolume = true)
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            levels += FolderLevel(
                name = part,
                documentId = prefix + parts.subList(0, index + 1).joinToString("/"),
            )
        }
        return levels
    }

    /**
     * The path as text, for copying and for pasting back in: `Download/2026` for
     * a level inside the grant, or [rootLabel] for the volume level itself. The
     * caller supplies the label because only the UI knows the localised name of
     * the primary volume.
     */
    public fun displayPath(levels: List<FolderLevel>, rootLabel: String): String =
        levels.joinToString("/") { level -> if (level.isVolume) rootLabel else level.name }

    /**
     * Interprets a typed or pasted path against the granted tree.
     *
     * Accepted: a SAF uri, the path [displayPath] produced (it starts with the
     * localised volume label), an absolute document id such as
     * `primary:Download`, the emulated volume's filesystem path, and — for a
     * relative path — first the volume root and then the grant as its base. The
     * first candidate that lands *inside the grant* wins, so neither reading of an
     * ambiguous relative path is lost, while an absolute path is never quietly
     * reinterpreted as something else.
     *
     * `..` is never resolved: a browser that walked up out of a grant would be
     * lying about what it is allowed to read.
     */
    public fun resolve(
        typed: String,
        treeUri: String,
        rootLabel: String = "",
    ): PathResolution {
        val root = rootDocumentIdOf(treeUri) ?: return PathResolution.Malformed
        val text = typed.trim().replace('\\', '/').trim('/')
        if (text.isEmpty()) return PathResolution.Inside(root)
        if (text.startsWith(SCHEME)) {
            val id = documentIdOf(text) ?: return PathResolution.Malformed
            return classify(id, root)
        }
        // Any other scheme is a url, not a path in this grant.
        if (text.contains("://")) return PathResolution.Malformed

        val segments = text.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            return PathResolution.Malformed
        }

        val volumeId = root.substringBefore(':', missingDelimiterValue = "")
        val volume = if (volumeId.isEmpty()) "" else "$volumeId:"
        val afterRootLabel = if (rootLabel.isNotBlank() && segments.first() == rootLabel) {
            segments.drop(1)
        } else {
            null
        }
        val afterEmulated = if (segments.take(EMULATED_ROOT.size) == EMULATED_ROOT) {
            segments.drop(EMULATED_ROOT.size)
        } else {
            null
        }
        val asDocumentId = segments.first().contains(':').takeIf { it }?.let { segments.joinToString("/") }

        val candidates = buildList {
            // Absolute readings, in the order a user is likely to have meant them.
            if (afterRootLabel != null) add(volume + afterRootLabel.joinToString("/"))
            if (afterEmulated != null) add(PRIMARY + ":" + afterEmulated.joinToString("/"))
            if (asDocumentId != null) add(asDocumentId)
            if (afterRootLabel == null && afterEmulated == null && asDocumentId == null) {
                add(volume + segments.joinToString("/"))
                add(childOf(root, segments.joinToString("/")))
            }
        }

        var sawOutside = false
        candidates.forEach { candidate ->
            when (val result = classify(candidate, root)) {
                is PathResolution.Inside -> return result
                PathResolution.Outside -> sawOutside = true
                PathResolution.Malformed -> Unit
            }
        }
        return if (sawOutside) PathResolution.Outside else PathResolution.Malformed
    }

    /** [relative] as a child of [root], honouring a volume root's trailing colon. */
    private fun childOf(root: String, relative: String): String = childPrefix(root) + relative

    private fun childPrefix(root: String): String =
        if (root.endsWith(':')) root else root.trimEnd('/') + "/"

    private fun classify(documentId: String, root: String): PathResolution = when {
        documentId.isBlank() -> PathResolution.Malformed
        documentId.any { it == '\u0000' } -> PathResolution.Malformed
        documentId.split('/').any { it == "." || it == ".." } -> PathResolution.Malformed
        documentId == root -> PathResolution.Inside(root)
        documentId.startsWith(childPrefix(root)) -> PathResolution.Inside(documentId)
        else -> PathResolution.Outside
    }

    private fun parse(uriString: String): Parsed? {
        if (!uriString.startsWith(SCHEME)) return null
        val rest = uriString.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val authority = rest.substring(0, slash)
        val path = rest.substring(slash + 1).substringBefore('?').substringBefore('#')
        // Split before decoding: an encoded %2F is part of one document id.
        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        if (authority.isEmpty() || segments.isEmpty()) return null
        return Parsed(base = SCHEME + authority, segments = segments)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
}
