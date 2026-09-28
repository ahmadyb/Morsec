package app.morsecode.core.model

/** Lifecycle of a browser session against the embedded WebShare server. */
public enum class BrowserSessionState(public val id: String) {
    /** Waiting for the phone user to accept; the token is not usable yet. */
    PENDING_APPROVAL("pending_approval"),
    ACTIVE("active"),
    REVOKED("revoked"),
    /** Ended by the browser; the token is dead immediately. */
    ENDED("ended"),
    ;

    public companion object {
        public fun fromId(id: String?): BrowserSessionState =
            entries.firstOrNull { it.id == id } ?: PENDING_APPROVAL
    }
}

/**
 * One browser session.
 *
 * [tokenDigest] is a SHA-256 of the bearer token: the raw token is shown once to
 * the browser and never persisted or logged. Approving, revoking and expiring
 * sessions are all real operations on the phone (master prompt §8.1, §8.5).
 */
public data class BrowserSession(
    val sessionId: String,
    val tokenDigest: String,
    val userAgent: String?,
    val remoteAddress: String,
    val state: BrowserSessionState = BrowserSessionState.PENDING_APPROVAL,
    val requestedEpochMillis: Long = 0L,
    val acceptedEpochMillis: Long = 0L,
    val revokedEpochMillis: Long = 0L,
    val lastActivityEpochMillis: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val bytesUploaded: Long = 0L,
    val requestCount: Int = 0,
) {
    val isLive: Boolean get() = state == BrowserSessionState.ACTIVE

    /** Browser label for the session list, e.g. "Chrome · Office Laptop". */
    val browserLabel: String
        get() = userAgent?.let { BrowserNames.fromUserAgent(it) } ?: remoteAddress
}

/** Minimal, offline user-agent classification. No network lookup, ever. */
public object BrowserNames {
    public fun fromUserAgent(userAgent: String): String {
        val ua = userAgent.lowercase()
        return when {
            "edg/" in ua || "edge" in ua -> "Edge"
            "opr/" in ua || "opera" in ua -> "Opera"
            "samsungbrowser" in ua -> "Samsung Internet"
            "firefox/" in ua || "fxios" in ua -> "Firefox"
            "crios" in ua || "chrome/" in ua -> "Chrome"
            "safari/" in ua -> "Safari"
            "curl/" in ua -> "curl"
            "wget" in ua -> "wget"
            else -> "Browser"
        }
    }

    /** Coarse device class, used only for the session row subtitle. */
    public fun deviceClass(userAgent: String): DeviceKind {
        val ua = userAgent.lowercase()
        return when {
            "ipad" in ua || "tablet" in ua -> DeviceKind.TABLET
            "android" in ua || "iphone" in ua || "mobile" in ua -> DeviceKind.PHONE
            else -> DeviceKind.DESKTOP
        }
    }
}

/**
 * Server state surfaced on the WebShare control screen. The address always
 * reflects the reachable local interface and the actually bound port, never a
 * remembered value.
 */
public data class WebShareStatus(
    val running: Boolean = false,
    val boundPort: Int = NetworkPorts.WEBSHARE_HTTP,
    /** Interface the server is reachable on, e.g. "wlan0". */
    val interfaceName: String? = null,
    /** Host address a browser should type, e.g. "192.168.1.24". */
    val hostAddress: String? = null,
    val startedEpochMillis: Long = 0L,
    val sessions: List<BrowserSession> = emptyList(),
    /** Set when the port could not be bound; the Doctor explains the fix. */
    val failureReason: String? = null,
) {
    /** "192.168.1.24:33455" or null when nothing is reachable. */
    val displayAddress: String? get() = hostAddress?.let { "$it:$boundPort" }

    /** Full http URL for the copy-to-clipboard action. */
    val displayUrl: String? get() = displayAddress?.let { "http://$it" }

    val liveSessionCount: Int get() = sessions.count { it.isLive }

    val pendingCount: Int get() = sessions.count { it.state == BrowserSessionState.PENDING_APPROVAL }
}

/**
 * State of one resumable WebShare upload or download (§12).
 *
 * Uploads land in a temporary partial file first, so `PARTIAL` can be resumed
 * after a page reload; a transfer is only `COMPLETED` once its SHA-256 has been
 * verified and the file has been moved into its target folder.
 */
public enum class WebTransferState(public val id: String) {
    PARTIAL("partial"),
    VERIFYING("verifying"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    ;

    public companion object {
        public fun fromId(id: String?): WebTransferState = entries.firstOrNull { it.id == id } ?: PARTIAL
    }
}

/**
 * A resumable WebShare upload or download as stored on the phone.
 *
 * The HTTP layer never keeps offsets in memory alone: a partial upload is
 * written to [partialPath] and its [receivedBytes] are persisted, so a page
 * reload resumes from a byte count both sides agree on (§12).
 */
public data class WebTransferRecord(
    val uploadId: String,
    val browserSessionId: String,
    val fileName: String,
    val targetDirectory: String,
    val totalBytes: Long,
    val receivedBytes: Long = 0L,
    val partialPath: String,
    val state: WebTransferState = WebTransferState.PARTIAL,
    val updatedEpochMillis: Long = 0L,
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0L) 0f else (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val isComplete: Boolean get() = state == WebTransferState.COMPLETED

    /** Byte offset a resumable `Range`/`Upload-Offset` request should continue from. */
    val resumeOffset: Long get() = if (state == WebTransferState.COMPLETED) totalBytes else receivedBytes
}
