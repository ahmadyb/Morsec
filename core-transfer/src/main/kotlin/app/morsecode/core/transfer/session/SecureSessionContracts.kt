package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId

/** Stable roles for the one-way selected-peer TLS upgrade. */
public enum class SecurePeerRole(public val wireId: Int) {
    INITIATOR(1),
    RESPONDER(2);

    public val opposite: SecurePeerRole
        get() = if (this == INITIATOR) RESPONDER else INITIATOR

    public companion object {
        public fun fromWireId(value: Int): SecurePeerRole? = entries.firstOrNull { it.wireId == value }
    }
}

/** Strict Part B bounds. These are deliberately independent of file-transfer limits. */
public object SecureSessionLimits {
    public const val DISCOVERY_WIRE_VERSION: Int = 1
    public const val CONTROL_WIRE_VERSION: Int = 1
    public const val SECURE_WIRE_VERSION: Int = 1
    public const val CAPABILITY_WIRE_VERSION: Int = 1
    public const val MAX_HELLO_FRAME_BYTES: Int = 512
    public const val MAX_TRANSCRIPT_BYTES: Int = 1_024
    public const val NONCE_BYTES: Int = 32
    public const val FINGERPRINT_BYTES: Int = 32
    public const val TRANSCRIPT_HASH_BYTES: Int = 32
    public const val APPROVAL_HANDLE_BYTES: Int = 16
    public const val MAX_RECORD_PLAINTEXT_BYTES: Int = 4_096
    public const val MAX_RECORDS_PER_DIRECTION: Long = 4_096L
    public const val MAX_BYTES_PER_DIRECTION: Long = 16_777_216L
    public const val MAX_SESSION_LIFETIME_MILLIS: Long = 300_000L
    public const val APPROVAL_LIFETIME_MILLIS: Long = 60_000L
    public const val TLS_HANDSHAKE_TIMEOUT_MILLIS: Int = 5_000
    public const val CONTROL_RECORD_IO_TIMEOUT_MILLIS: Int = 5_000
    public const val KEY_CONFIRMATION_BYTES: Int = 38
    public const val AEAD_TAG_BYTES: Int = 16
    public const val NONCE_BYTES_FOR_AES_GCM: Int = 12
    public const val AES_KEY_BYTES: Int = 16
}

/** Production uses SecureRandom; deterministic tests inject a fixed byte source. */
public fun interface SecureEntropy {
    public fun nextBytes(destination: ByteArray)
}

/**
 * Human-comparable, session-only short authentication string (SAS).
 *
 * The code is five symbols drawn from a 32-symbol alphabet, so it carries exactly
 * [SAS_ENTROPY_BITS] = 25 bits of comparison entropy. That number is the whole security budget of
 * the human check: an attacker who can complete a handshake and simply guess the code succeeds with
 * probability 2^-25 per attempt, and the only thing limiting the number of attempts is the
 * process-local attempt limiter, which is a denial-of-service bound and not authentication.
 *
 * The previous six-digit decimal form carried 19 bits — 10^6 is not 2^20, and the zero-padded
 * decimal rendering wasted roughly 0.8 bits per symbol. Base 32 over an unambiguous alphabet
 * recovers that and, more importantly, removes the character pairs a human is likely to misread.
 *
 * The alphabet deliberately excludes 0 and O, and 1 and I, so no symbol in a correctly transcribed
 * code can be confused with another. Comparison must still be done by a human reading both devices;
 * nothing here authenticates the human.
 */
public class HumanVerificationCode private constructor(value: CharArray) {
    private val symbols: CharArray = value.copyOf()
    private var cleared: Boolean = false

    init {
        require(symbols.size == SAS_SYMBOL_COUNT) { "SAS must be $SAS_SYMBOL_COUNT symbols" }
        require(symbols.all { it in SAS_ALPHABET }) { "SAS contains a symbol outside the alphabet" }
    }

    /** The only API that reveals the short code; callers must not log or persist the result. */
    @Synchronized
    public fun displayText(): String = if (cleared) "" else String(symbols)

    @Synchronized
    public fun clearSensitive() {
        symbols.fill('\u0000')
        cleared = true
    }

    internal fun clear() = clearSensitive()

    override fun toString(): String = "HumanVerificationCode([redacted])"

    public companion object {
        /**
         * Crockford-style base-32 without 0, O, 1 and I. Length 32, so each symbol is exactly
         * 5 bits and no symbol is visually ambiguous with another.
         */
        public const val SAS_ALPHABET: String = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        public const val SAS_SYMBOL_COUNT: Int = 5

        /** 5 symbols x 5 bits. Documented so no future change can silently shrink the budget. */
        public const val SAS_ENTROPY_BITS: Int = SAS_SYMBOL_COUNT * 5

        private const val ALPHABET_RADIX: Int = 32

        private const val MINIMUM_EXPORTER_BYTES: Int = 4

        /**
         * Converts TLS exporter output into the SAS by taking the leading [SAS_ENTROPY_BITS] bits.
         *
         * Requires [MINIMUM_EXPORTER_BYTES] bytes: three bytes supply 24 bits and the most
         * significant bit of the fourth supplies the 25th. The remaining bits are discarded rather
         * than folded in, because folding would make the mapping harder to reason about without
         * adding entropy. The input buffer is wiped whether conversion succeeds or fails.
         */
        public fun fromExporterMaterial(material: ByteArray): HumanVerificationCode {
            try {
                require(material.size >= MINIMUM_EXPORTER_BYTES) {
                    "exporter output is too short: ${material.size} < $MINIMUM_EXPORTER_BYTES"
                }
                val value =
                    ((material[0].toInt() and 0xFF) shl 17) or
                        ((material[1].toInt() and 0xFF) shl 9) or
                        ((material[2].toInt() and 0xFF) shl 1) or
                        ((material[3].toInt() and 0x80) ushr 7)
                val chars = CharArray(SAS_SYMBOL_COUNT)
                var remaining = value
                for (index in chars.lastIndex downTo 0) {
                    chars[index] = SAS_ALPHABET[remaining % ALPHABET_RADIX]
                    remaining /= ALPHABET_RADIX
                }
                return try {
                    HumanVerificationCode(chars)
                } finally {
                    chars.fill('\u0000')
                }
            } finally {
                material.fill(0)
            }
        }
    }
}

private fun copyApprovalToken(token: ByteArray): ByteArray {
    require(token.size == SecureSessionLimits.APPROVAL_HANDLE_BYTES)
    return token.copyOf()
}

/** Opaque, non-serializable, exact-request capability. Its identity is bound to one transcript. */
public class SecureApprovalHandle internal constructor(token: ByteArray) {
    private val secret: ByteArray = copyApprovalToken(token)

    internal fun matches(candidate: SecureApprovalHandle): Boolean =
        this === candidate && java.security.MessageDigest.isEqual(secret, candidate.secret)

    internal fun clear() {
        secret.fill(0)
    }

    override fun toString(): String = "SecureApprovalHandle([redacted])"
}

/** One approval prompt. It intentionally has no transcript bytes, digest, exporter, or key. */
public class SecurePairingApprovalRequest internal constructor(
    public val controlSessionId: SessionId,
    public val secureSessionId: SessionId,
    public val localPeerInstanceId: PeerInstanceId,
    public val remotePeerInstanceId: PeerInstanceId,
    public val proof: HumanVerificationCode,
    public val expiresAtElapsedMillis: Long,
    public val handle: SecureApprovalHandle,
) {
    override fun toString(): String =
        "SecurePairingApprovalRequest(session=[redacted], proof=[redacted], expiresAt=$expiresAtElapsedMillis)"
}

public enum class SecureApprovalDecision {
    APPROVE,
    REJECT,
}

public sealed interface SecureApprovalResult {
    public data object Applied : SecureApprovalResult
    public data object AlreadyApplied : SecureApprovalResult
    public data object Stale : SecureApprovalResult
    public data object Conflict : SecureApprovalResult
    public data object Expired : SecureApprovalResult
    public data object NotReady : SecureApprovalResult
}

public enum class SecurePairingState {
    TRANSCRIPT_PENDING,
    AWAITING_LOCAL_APPROVAL,
    APPROVED,
    CONFIRMING,
    AUTHENTICATED,
    REJECTED,
    EXPIRED,
    FAILED,
    CANCELLED,
}

/**
 * Outcome reported to the pairing listener.
 *
 * `Authenticated` is deliberately a status-only object: it reports that the reducer reached the
 * authenticated phase and carries nothing else. It used to carry a `NegotiatedSession` whose
 * security marker was treated as authority, which let a public caller mint the appearance of an
 * authenticated session without performing the handshake. Post-pairing authority is the opaque
 * secure control channel, which is internal to :transport-lan and is only produced by a protected
 * write followed by an authenticated peer confirmation read.
 */
public sealed interface SecurePairingResult {
    /** Informational only. Confers no capability and grants no payload authority. */
    public data object Authenticated : SecurePairingResult

    public class Failed(public val failure: SessionFailure) : SecurePairingResult {
        override fun toString(): String = "SecurePairingResult.Failed(${failure.code.id})"
    }
}

public sealed interface SecureControlSendResult {
    public data object Sent : SecureControlSendResult
    public class Refused(public val failure: SessionFailure) : SecureControlSendResult {
        override fun toString(): String = "SecureControlSendResult.Refused(${failure.code.id})"
    }
}

public sealed interface SecureControlReceiveResult {
    public class Record(public val value: SecureControlRecord) : SecureControlReceiveResult {
        override fun toString(): String = "SecureControlReceiveResult.Record([redacted])"
    }

    public class Refused(public val failure: SessionFailure) : SecureControlReceiveResult {
        override fun toString(): String = "SecureControlReceiveResult.Refused(${failure.code.id})"
    }
}
