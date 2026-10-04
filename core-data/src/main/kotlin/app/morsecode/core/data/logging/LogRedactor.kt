// The "/sdcard/" literal below is a redaction *pattern*: LogRedactor has to
// recognise the legacy alias in whatever text another component logged. Lint's
// SdCardPath check reads it as a hardcoded path the app opens, which it is not.
@file:SuppressLint("SdCardPath")

package app.morsecode.core.data.logging

import android.annotation.SuppressLint

/**
 * Redaction applied to every log message and stack trace before it is written.
 *
 * The master prompt (§10) forbids logging session tokens, bearer secrets,
 * digest values, content URIs and private absolute paths. The Logs screen offers
 * a Share action, so the text a user exports must already be safe. Pure Kotlin,
 * no Android APIs, so it
 * is covered by an ordinary JVM unit test.
 */
public object LogRedactor {

    private const val MARKER = "[redacted]"

    /**
     * `Authorization: …` — the value is redacted whole, because a header value is
     * `Bearer <token>` and stopping at the first space would keep the secret.
     */
    private val authorizationHeaders = Regex(
        """(?i)\b(authorization)\b(\s*[=:]\s*)([^\n,;]+)""",
    )

    /** A bare `Bearer <token>` with no header name in front of it. */
    private val bearerTokens = Regex("""(?i)\b(bearer)\s+([A-Za-z0-9_\-.+/=]{6,})""")

    /** `?token=abc…`, `sessionId=abc…`, `password=abc…`. */
    private val secretAssignments = Regex(
        """(?i)\b(token|secret|password|passwd|pin|apikey|api_key|""" +
            """session[_-]?token|session[_-]?id|upload[_-]?id)\b(\s*[=:]\s*)([^\s,&;""']+)(["']?)""",
    )

    /** `?token=abc&…` inside a URL, where the key is preceded by `?` or `&`. */
    private val querySecrets = Regex(
        """(?i)([?&])(token|access_token|session|sig|signature)=([^&#\s]+)""",
    )

    /** Device identifiers that are stable across installs. */
    private val identifiers = Regex(
        """(?i)\b(android[_-]?id|imei|serial|serialno|gaid|advertising[_-]?id)\b(\s*[=:]\s*)([^\s,;""']+)""",
    )

    /** App-private file paths are replaced whole, including their child names. */
    private val appDataPaths = Regex(
        """(?i)(?<![\w:])/data/(?:user/\d+|data)/[\w.]+(?:/(?:files|cache|databases|no_backup))?(?:/[^\s,;"'<>]*)?""",
    )

    /** Shared-storage paths are private too; do not retain the filename suffix. */
    private val sharedStoragePaths = Regex(
        """(?i)(?<![\w:])/storage/(?:emulated/\d+|[a-z0-9-]{4,})(?:/[^\s,;"'<>]*)?""",
    )

    /** `/sdcard/…` legacy alias. */
    private val sdcardPaths = Regex("""(?i)(?<![\w:])/sdcard(?:/[^\s,;"'<>]*)?""")

    /** Common host-private absolute paths that can appear in diagnostics. */
    private val hostPrivatePaths = Regex(
        """(?i)(?<![\w:])/(?:home|users|private/var|tmp|var/tmp)/[^\s,;"'<>]+""",
    )

    /** File URIs and SAF/content URIs can encode private folder and document names. */
    private val fileUris = Regex("""(?i)\bfile://[^\s<>"']+""")
    private val contentUris = Regex("""(?i)\bcontent://[^\s<>"']+""")

    /** Long opaque blobs, including digest bytes, are not log-safe. */
    private val opaqueBlobs = Regex(
        """(?<![\w])(?:[A-Za-z0-9_+-]{40,}|[A-Za-z0-9_+-]{24,}(?:[/=][A-Za-z0-9_+-]{1,})+(?:=*)?)(?![\w])""",
    )

    /** Keep shared/exported logs free of terminal and control characters. */
    private val controlCharacters = Regex("""\p{Cc}""")

    public fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var working = controlCharacters.replace(input, " ")
        working = fileUris.replace(working, "<file-uri>")
        working = contentUris.replace(working, "content://[redacted]")
        working = authorizationHeaders.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MARKER" }
        working = bearerTokens.replace(working) { m -> "${m.groupValues[1]} $MARKER" }
        working = secretAssignments.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MARKER${m.groupValues[4]}" }
        working = querySecrets.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}=$MARKER" }
        working = identifiers.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MARKER" }
        working = appDataPaths.replace(working, "<app-data>")
        working = sharedStoragePaths.replace(working, "<storage>")
        working = sdcardPaths.replace(working, "<storage>")
        working = hostPrivatePaths.replace(working, "<private-path>")
        working = opaqueBlobs.replace(working, MARKER)
        return working
    }

    /**
     * Redacts a stack trace and truncates it. Frames below the app's own
     * packages are dropped first, because they are framework noise in a shared
     * export.
     */
    public fun redactStackTrace(throwable: Throwable, maxLines: Int = 60): String {
        val lines = mutableListOf<String>()
        var current: Throwable? = throwable
        var guard = 0
        while (current != null && guard < 5) {
            if (lines.isNotEmpty()) lines += "Caused by: ${current.javaClass.name}: ${redact(current.message)}"
            else lines += "${current.javaClass.name}: ${redact(current.message)}"
            val frames = current.stackTrace
            val own = frames.filter { it.className.startsWith("app.morsecode") }
            val keep = if (own.size >= 3) own else frames.toList()
            keep.take(maxLines).forEach { lines += "\tat ${redact(it.toString())}" }
            val dropped = keep.size - maxLines.coerceAtMost(keep.size)
            if (dropped > 0) lines += "\t… $dropped more"
            current = current.cause
            guard++
        }
        return redact(lines.joinToString("\n"))
    }
}
