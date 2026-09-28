package app.morsecode.core.model

/**
 * A finished transfer, kept after its session ended.
 *
 * The History destination shows exactly these rows (master prompt §5): received
 * and sent are separate tabs over the same table, and a row is only created once
 * an item reaches a terminal state — an in-flight file never appears here.
 */
public data class HistoryEntry(
    val historyId: String,
    val transferId: String,
    val sessionId: String?,
    val direction: SessionDirection,
    val displayName: String,
    val mimeType: String? = null,
    val kind: MediaKind = MediaKind.OTHER,
    val totalBytes: Long = 0L,
    val peerId: String? = null,
    val peerName: String? = null,
    val state: TransferState = TransferState.COMPLETED,
    val failureReason: String? = null,
    val sha256Hex: String? = null,
    val finishedEpochMillis: Long = 0L,
    /** Uri string of the committed file, when one exists and is still readable. */
    val resultUriString: String? = null,
    /** Non-null when the transfer was part of a 1→N broadcast. */
    val broadcastId: String? = null,
) {
    val isSuccess: Boolean get() = state == TransferState.COMPLETED

    val isFailure: Boolean get() = state == TransferState.FAILED_FINAL || state == TransferState.FAILED_RETRYABLE

    /**
     * Row subtitle: "Ravi's Redmi · 4.1 MB". The peer is omitted for rows whose
     * session was deleted, and the size for zero byte entries. Formatting is
     * injected so the phone can pass its locale aware [MorseFormatters] while
     * the WebShare JSON API passes the English one.
     */
    public fun subtitle(formatters: MorseFormatters): String = listOfNotNull(
        peerName?.takeIf { it.isNotBlank() },
        if (totalBytes > 0L) formatters.bytes(totalBytes) else null,
    ).joinToString(" · ")

    public companion object {
        /** Builds a history row from a finished item plus its session context. */
        public fun of(
            item: TransferItem,
            peer: Peer?,
            sessionId: String?,
            broadcastId: String? = null,
        ): HistoryEntry = HistoryEntry(
            historyId = "${item.transferId}:${directionId(item, peer)}",
            transferId = item.transferId,
            sessionId = sessionId,
            direction = item.direction,
            displayName = item.displayName,
            mimeType = item.mimeType,
            kind = item.kind,
            totalBytes = item.totalBytes,
            peerId = peer?.peerId,
            peerName = peer?.displayName,
            state = item.state,
            failureReason = item.failureReason,
            sha256Hex = item.sha256Hex,
            finishedEpochMillis = item.finishedEpochMillis,
            resultUriString = item.resultUriString,
            broadcastId = broadcastId,
        )

        private fun directionId(item: TransferItem, peer: Peer?): String =
            "${item.direction.id}:${peer?.peerId ?: "unknown"}"
    }
}

/**
 * A device the user has transferred with.
 *
 * Shown under "Recent devices" on the Connect destination so a repeat transfer
 * does not require re-scanning, and used to label history rows after the peer
 * has disappeared from discovery.
 */
public data class RecentDevice(
    val peerId: String,
    val displayName: String,
    val transport: TransportKind,
    val deviceKind: DeviceKind = DeviceKind.UNKNOWN,
    val detail: String = "",
    val lastSeenEpochMillis: Long = 0L,
    val lastSummary: String? = null,
    val interactionCount: Int = 0,
) {
    val letter: Char get() = displayName.trim().firstOrNull()?.uppercaseChar() ?: '?'

    public companion object {
        /** Records an interaction with [peer], bumping the count and timestamp. */
        public fun of(peer: Peer, summary: String?, previousCount: Int): RecentDevice = RecentDevice(
            peerId = peer.peerId,
            displayName = peer.displayName,
            transport = peer.transport,
            deviceKind = peer.deviceKind,
            detail = peer.detail,
            lastSeenEpochMillis = peer.lastSeenEpochMillis,
            lastSummary = summary,
            interactionCount = previousCount + 1,
        )
    }
}

/**
 * A persisted Storage Access Framework tree grant.
 *
 * Grants are re-acquired with `takePersistableUriPermission` at start-up, and a
 * revoked grant is surfaced by the Connection Doctor instead of failing silently
 * during a transfer (master prompt §7).
 */
public data class SafGrant(
    val id: Long = 0L,
    val treeUri: String,
    val displayName: String,
    val grantedEpochMillis: Long = 0L,
    val readWrite: Boolean = true,
)
