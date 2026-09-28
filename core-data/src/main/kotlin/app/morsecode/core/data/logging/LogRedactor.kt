package app.morsecode.core.data.logging

/**
 * Redaction applied to every log message and stack trace before it is written.
 *
 * The master prompt (§10) forbids logging session tokens, bearer secrets and
 * private absolute paths, and the Logs screen offers a Share action — so the
 * text a user exports must already be safe. Pure Kotlin, no Android APIs, so it
 * is covered by an ordinary JVM unit test.
 */
public object LogRedactor {

    private const val MARKER = "[redacted]"

    /** `Authorization: Bearer abc…`, `?token=abc…`, `sessionId=abc…`. */
    private val secretAssignments = Regex(
        """(?i)\b(bearer|token|secret|password|passwd|pin|apikey|api_key|authorization|""" +
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

    /** App private storage: `/data/user/0/app.morsecode/…` or `/data/data/…`. */
    private val appDataPaths = Regex("""/data/(?:user/\d+|data)/[\w.]+/(?:files|cache|databases|no_backup)?/?""")

    /** Shared storage root: `/storage/emulated/0/…`. */
    private val sharedStoragePaths = Regex("""/storage/(?:emulated/\d+|[A-Z0-9-]{4,})/""")

    /** `/sdcard/…` legacy alias. */
    private val sdcardPaths = Regex("""/sdcard/""")

    /**
     * Long opaque blobs (bearer-style tokens, base64 payloads). 40+ characters
     * is above any human readable word the app logs, and below a SHA-256 hex
     * digest length only when it is not hex; digests are kept because the
     * verification step must be auditable.
     */
    private val opaqueBlobs = Regex("""\b[A-Za-z0-9_\-]{40,}\b""")

    /** SHA-256 digests are useful and not secret: never redact them. */
    private val sha256Hex = Regex("""\b[a-fA-F0-9]{64}\b""")

    public fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        // Protect digests first so the opaque-blob rule cannot eat them.
        val digests = mutableListOf<String>()
        var working = sha256Hex.replace(input) { match ->
            digests += match.value
            "\u0000DIGEST${digests.size - 1}\u0000"
        }
        working = secretAssignments.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MARKER${m.groupValues[4]}" }
        working = querySecrets.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}=$MARKER" }
        working = identifiers.replace(working) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MARKER" }
        working = appDataPaths.replace(working, "<app-data>/")
        working = sharedStoragePaths.replace(working, "<storage>/")
        working = sdcardPaths.replace(working, "<storage>/")
        working = opaqueBlobs.replace(working, MARKER)
        digests.forEachIndexed { index, digest ->
            working = working.replace("\u0000DIGEST$index\u0000", digest)
        }
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
