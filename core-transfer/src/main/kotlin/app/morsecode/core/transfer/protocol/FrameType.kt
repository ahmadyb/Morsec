package app.morsecode.core.transfer.protocol

/**
 * Every frame type in protocol version 1.
 *
 * The ordinal is the on-the-wire value; it is part of the format and must never
 * be renumbered. Unknown values are a decode error rather than a default, so a
 * newer peer cannot silently make an older build misread a frame.
 */
public enum class FrameType(
    public val id: Int,
    public val wireName: String,
    /** True when the frame carries a payload the payload CRC32 must cover. */
    public val hasPayload: Boolean,
) {
    /** Sender → receiver: capabilities, maximum chunk size, resume support. */
    HANDSHAKE(1, "handshake", hasPayload = true),

    /** Receiver → sender: accepted, with the chunk size to use. */
    HANDSHAKE_ACCEPT(2, "handshake_accept", hasPayload = true),

    /** Receiver → sender: declined, with a bounded reason. */
    HANDSHAKE_REJECT(3, "handshake_reject", hasPayload = true),

    /** Sender → receiver: the file descriptor that must be agreed first. */
    FILE_METADATA(4, "file_metadata", hasPayload = true),

    /** Sender → receiver: what I intend to send, so resume can be agreed. */
    RESUME_PROPOSAL(5, "resume_proposal", hasPayload = true),

    /** Receiver → sender: where to start (or why we must start over). */
    RESUME_RESPONSE(6, "resume_response", hasPayload = true),

    /** Raw file bytes; the only frame whose payload is unbounded-ish. */
    DATA_CHUNK(7, "data_chunk", hasPayload = true),

    /** Receiver → sender: chunk accepted and the new confirmed offset. */
    CHUNK_ACK(8, "chunk_ack", hasPayload = true),

    /** Either direction: stop sending. */
    PAUSE(9, "pause", hasPayload = true),

    /** Either direction: start again from the named offset. */
    RESUME(10, "resume", hasPayload = true),

    /** Either direction: this delivery is over. */
    CANCEL(11, "cancel", hasPayload = true),

    /** Either direction: a typed failure. */
    ERROR(12, "error", hasPayload = true),

    /** Receiver → sender: the full-file SHA-256 outcome. */
    VERIFICATION_RESULT(13, "verification_result", hasPayload = true),

    /** Sender → receiver: this file is finished and verified here. */
    FILE_COMPLETE(14, "file_complete", hasPayload = true),

    /** Either direction: the whole batch is finished. */
    SESSION_COMPLETE(15, "session_complete", hasPayload = true),
    ;

    public companion object {
        /** Non-throwing lookup used by the decoder on untrusted input. */
        public fun fromId(id: Int): FrameType? = entries.firstOrNull { it.id == id }

        public fun fromWireName(name: String): FrameType? =
            entries.firstOrNull { it.wireName == name }

        /** All fifteen types, in wire order; used by the round-trip tests. */
        public val all: List<FrameType> = entries.toList()
    }
}
