package app.morsecode.core.model

/**
 * Fixed local-network ports and timings.
 *
 * These are the defaults the approved mockup displays (`http://…:33455`,
 * `PORT 33456`, `beacon every 1200 ms`). They are configurable only through the
 * WebShare port setting, and a conflict is reported by the Connection Doctor
 * rather than silently moved.
 */
public object NetworkPorts {
    /** Embedded WebShare HTTP server. */
    public const val WEBSHARE_HTTP: Int = 33455

    /** Peer control channel (handshake, descriptors, ACKs) and data channel. */
    public const val PEER_CONTROL: Int = 33456

    /** UDP discovery beacon. */
    public const val DISCOVERY_BEACON: Int = 33457

    /** Beacon interval while actively discovering. */
    public const val BEACON_INTERVAL_MILLIS: Long = 1_200L

    /** A peer that has not beaconed for this long is treated as gone. */
    public const val PEER_STALE_AFTER_MILLIS: Long = BEACON_INTERVAL_MILLIS * 4

    /** Ports the user may select for WebShare. */
    public val USER_PORT_RANGE: IntRange = 1024..65535

    /** True when [port] is one of the three fixed Morsecode ports. */
    public fun isMorsecodePort(port: Int): Boolean =
        port == WEBSHARE_HTTP || port == PEER_CONTROL || port == DISCOVERY_BEACON

    /** All ports that must be free for a full LAN + WebShare session. */
    public val requiredPorts: List<Int> = listOf(WEBSHARE_HTTP, PEER_CONTROL, DISCOVERY_BEACON)
}

/**
 * Direction of an item inside a duplex session. A connected session is always
 * bidirectional: either side can add files without pairing again.
 */
public enum class SessionDirection(public val id: String) {
    OUTBOUND("outbound"),
    INBOUND("inbound"),
    ;

    public companion object {
        public fun fromId(id: String?): SessionDirection = entries.firstOrNull { it.id == id } ?: OUTBOUND
    }
}

/** What a session is doing overall; drives the screen title and notification. */
public enum class SessionPhase(public val id: String) {
    /** Created locally, waiting for the peer to accept. */
    PENDING_CONSENT("pending_consent"),
    CONNECTING("connecting"),
    ACTIVE("active"),
    /** Every item reached a terminal state; the session is still open. */
    COMPLETE("complete"),
    ENDED("ended"),
    FAILED("failed"),
    ;

    public companion object {
        public fun fromId(id: String?): SessionPhase = entries.firstOrNull { it.id == id } ?: PENDING_CONSENT
    }
}

/**
 * One file (or one folder streamed as an archive) inside a session.
 *
 * [transferredBytes] is only ever advanced by a receiver-confirmed offset, never
 * by the sender's optimistic write position — that is what makes resume correct
 * after an interruption.
 */
public data class TransferItem(
    val transferId: String,
    val sessionId: String,
    val batchId: String,
    val direction: SessionDirection,
    val displayName: String,
    /** Relative path inside a folder transfer; empty for a single file. */
    val relativePath: String = "",
    val mimeType: String? = null,
    val kind: MediaKind = MediaKind.OTHER,
    val totalBytes: Long = 0L,
    val lastModifiedEpochMillis: Long = 0L,
    val isFolderArchive: Boolean = false,
    val state: TransferState = TransferState.QUEUED,
    /** Last offset acknowledged by the receiving side. */
    val confirmedBytes: Long = 0L,
    /** Rolling throughput sample in bytes per second, 0 when idle. */
    val bytesPerSecond: Long = 0L,
    /** SHA-256 of the whole file, filled in during VERIFYING. */
    val sha256Hex: String? = null,
    /** Failure reason id resolved to a string resource by the UI. */
    val failureReason: String? = null,
    /** Number of times this item has been retried; retry is per file. */
    val retryCount: Int = 0,
    val queuedEpochMillis: Long = 0L,
    val finishedEpochMillis: Long = 0L,
    /** Uri string of the committed file once COMPLETED. */
    val resultUriString: String? = null,
    /** For broadcast batches: which receiver this delivery belongs to. */
    val recipientPeerId: String? = null,
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0L) 0f else (confirmedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val progressPercent: Int get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val isComplete: Boolean get() = state == TransferState.COMPLETED

    val actions: ItemActions get() = state.itemActions()
}

/**
 * A duplex session with one peer. Broadcasts create one [TransferSession] per
 * recipient, linked by [broadcastId], so a slow or failed receiver can never
 * block the others.
 */
public data class TransferSession(
    val sessionId: String,
    val peer: Peer,
    val phase: SessionPhase = SessionPhase.PENDING_CONSENT,
    val directionHint: SessionDirection = SessionDirection.OUTBOUND,
    /** Non-null when this session is one delivery of a 1→N broadcast. */
    val broadcastId: String? = null,
    val startedEpochMillis: Long = 0L,
    val endedEpochMillis: Long = 0L,
    /** True while the user has paused every eligible item in this session. */
    val pauseAll: Boolean = false,
    val failureReason: String? = null,
)

/** Aggregate numbers shown in the summary card under a session's file list. */
public data class SessionSummary(
    val sending: Int = 0,
    val receiving: Int = 0,
    val queued: Int = 0,
    val paused: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val cancelled: Int = 0,
    val bytesTransferred: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
) {
    val isComplete: Boolean
        get() = (sending + receiving + queued + paused + failed) == 0 && (completed + skipped + cancelled) > 0

    public companion object {
        /** Builds the summary from real items; the UI never invents numbers. */
        public fun of(items: List<TransferItem>): SessionSummary = SessionSummary(
            sending = items.count { it.direction == SessionDirection.OUTBOUND && it.state.isBusy },
            receiving = items.count { it.direction == SessionDirection.INBOUND && it.state.isBusy },
            queued = items.count { it.state == TransferState.QUEUED || it.state == TransferState.NEGOTIATING },
            paused = items.count { it.state.isPaused },
            completed = items.count { it.state == TransferState.COMPLETED },
            failed = items.count { it.state == TransferState.FAILED_RETRYABLE || it.state == TransferState.FAILED_FINAL },
            skipped = items.count { it.state == TransferState.SKIPPED },
            cancelled = items.count { it.state == TransferState.CANCELLED },
            bytesTransferred = items.sumOf { it.confirmedBytes },
            bytesTotal = items.sumOf { it.totalBytes },
            bytesPerSecond = items.filter { it.state.isBusy }.sumOf { it.bytesPerSecond },
        )
    }
}
