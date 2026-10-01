package app.morsecode.core.transfer.identity

import app.morsecode.core.transfer.ProtocolLimits

/**
 * A protocol version number as it appears on the wire and in a handshake.
 *
 * Version 1 is the only version this build produces. The pair
 * [PROTOCOL_VERSION_MIN] / [ProtocolLimits.PROTOCOL_VERSION_MAX] is the
 * compatibility window used by the handshake: a peer inside the window is
 * accepted at the lower of the two versions, a peer outside it is rejected with
 * `TransferError.ProtocolVersionMismatch` instead of being guessed at.
 */
public data class ProtocolVersion(public val value: Int) {
    init {
        require(value >= ProtocolLimits.PROTOCOL_VERSION_MIN) {
            "protocol version $value is older than the supported minimum ${ProtocolLimits.PROTOCOL_VERSION_MIN}"
        }
        require(value <= ProtocolLimits.PROTOCOL_VERSION_MAX) {
            "protocol version $value is newer than the supported maximum ${ProtocolLimits.PROTOCOL_VERSION_MAX}"
        }
    }

    /** True when a peer speaking [other] can be talked to at all. */
    public fun isCompatibleWith(other: ProtocolVersion): Boolean =
        value <= other.value || other.value >= ProtocolLimits.PROTOCOL_VERSION_MIN

    /**
     * The version two peers should actually use: the highest version both
     * support, which for a compatible pair is simply the lower of the two.
     */
    public fun negotiateWith(other: ProtocolVersion): ProtocolVersion =
        ProtocolVersion(minOf(value, other.value))

    override fun toString(): String = value.toString()

    public companion object {
        /** The version every frame this build emits carries. */
        public val CURRENT: ProtocolVersion = ProtocolVersion(ProtocolLimits.PROTOCOL_VERSION_CURRENT)

        /** Oldest version this build will still decode. */
        public val MIN: ProtocolVersion = ProtocolVersion(ProtocolLimits.PROTOCOL_VERSION_MIN)

        /** Newest version this build will still decode. */
        public val MAX: ProtocolVersion = ProtocolVersion(ProtocolLimits.PROTOCOL_VERSION_MAX)

        public fun isValid(value: Int): Boolean =
            value >= ProtocolLimits.PROTOCOL_VERSION_MIN && value <= ProtocolLimits.PROTOCOL_VERSION_MAX

        /** Non-throwing factory, used by the codec on untrusted input. */
        public fun orNull(value: Int): ProtocolVersion? =
            if (isValid(value)) ProtocolVersion(value) else null
    }
}
