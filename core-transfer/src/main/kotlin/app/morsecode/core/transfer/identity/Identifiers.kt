package app.morsecode.core.transfer.identity

import app.morsecode.core.transfer.ProtocolLimits

/*
 * Transfer identities.
 *
 * Every identifier is a bounded, validated UTF-8 string rather than an opaque
 * `String`, for one reason: identifiers arrive from a peer, and an unbounded
 * peer-supplied string is the cheapest way to blow up an allocation or to smuggle
 * a path into a filename. The validation lives in the constructor, so an
 * identifier that exists is an identifier that is safe to put in a frame, a
 * filename, a database row and a log line.
 *
 * Deliberately *not* exposed anywhere in this package: absolute filesystem
 * paths. A transfer names a file by its [RelativeTransferPath]; resolving it to
 * a real location is a storage-adapter concern that arrives later.
 */

/** Letters, digits and the four separators a transfer identifier may use. */
private val IDENTIFIER_PATTERN = Regex("""[A-Za-z0-9._:@-]+""")

/**
 * Validates an untrusted identifier, throwing [IllegalArgumentException] with a
 * message that names the field but never echoes the offending value (which may
 * be hostile and arbitrarily long).
 */
internal fun requireValidIdentifier(field: String, raw: String) {
    val encoded = raw.toByteArray(Charsets.UTF_8)
    require(encoded.isNotEmpty()) { "$field must not be empty" }
    require(encoded.size <= ProtocolLimits.MAX_ID_LENGTH_BYTES) {
        "$field must not exceed ${ProtocolLimits.MAX_ID_LENGTH_BYTES} UTF-8 bytes"
    }
    require(IDENTIFIER_PATTERN.matches(raw)) {
        "$field may only contain letters, digits and the characters . _ : @ -"
    }
}

internal fun isValidIdentifier(raw: String): Boolean {
    val encoded = raw.toByteArray(Charsets.UTF_8)
    return encoded.isNotEmpty() &&
        encoded.size <= ProtocolLimits.MAX_ID_LENGTH_BYTES &&
        IDENTIFIER_PATTERN.matches(raw)
}

/**
 * One duplex connection between two devices, or one delivery leg of a
 * broadcast (a broadcast creates one session per recipient).
 */
public data class SessionId(public val value: String) {
    init {
        requireValidIdentifier(FIELD, value)
    }

    override fun toString(): String = value

    public companion object {
        public const val FIELD: String = "sessionId"
        public fun isValid(raw: String): Boolean = isValidIdentifier(raw)
        public fun orNull(raw: String): SessionId? = if (isValid(raw)) SessionId(raw) else null
    }
}

/** The set of files a user selected in one action; a broadcast's batch. */
public data class BatchId(public val value: String) {
    init {
        requireValidIdentifier(FIELD, value)
    }

    override fun toString(): String = value

    public companion object {
        public const val FIELD: String = "batchId"
        public fun isValid(raw: String): Boolean = isValidIdentifier(raw)
        public fun orNull(raw: String): BatchId? = if (isValid(raw)) BatchId(raw) else null
    }
}

/** One file delivery: the unit the state machine, the queue and the UI own. */
public data class TransferId(public val value: String) {
    init {
        requireValidIdentifier(FIELD, value)
    }

    override fun toString(): String = value

    public companion object {
        public const val FIELD: String = "transferId"
        public fun isValid(raw: String): Boolean = isValidIdentifier(raw)
        public fun orNull(raw: String): TransferId? = if (isValid(raw)) TransferId(raw) else null
    }
}

/**
 * The stable identity of the *content* being moved.
 *
 * Distinct from [TransferId]: a broadcast sends the same file to N recipients
 * under N transfer ids but one file id, which is what lets the broadcast
 * aggregate count "3 of 4 recipients have this file" without double counting.
 */
public data class FileId(public val value: String) {
    init {
        requireValidIdentifier(FIELD, value)
    }

    override fun toString(): String = value

    public companion object {
        public const val FIELD: String = "fileId"
        public fun isValid(raw: String): Boolean = isValidIdentifier(raw)
        public fun orNull(raw: String): FileId? = if (isValid(raw)) FileId(raw) else null
    }
}

/** One broadcast delivery leg. Null for a plain duplex transfer. */
public data class RecipientId(public val value: String) {
    init {
        requireValidIdentifier(FIELD, value)
    }

    override fun toString(): String = value

    public companion object {
        public const val FIELD: String = "recipientId"
        public fun isValid(raw: String): Boolean = isValidIdentifier(raw)
        public fun orNull(raw: String): RecipientId? = if (isValid(raw)) RecipientId(raw) else null
    }
}
