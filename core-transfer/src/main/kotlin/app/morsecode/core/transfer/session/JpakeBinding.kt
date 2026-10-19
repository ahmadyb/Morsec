/*
 * Experimental EC J-PAKE channel binding (Candidate B, project-selected).
 *
 * WHAT THIS IS
 *   One canonical digest that ties the pairing exchange to the TLS channel and to the negotiated
 *   session, plus deterministic participant identifiers derived from it.
 *
 * WHY IT EXISTS
 *   The library's zero-knowledge proofs and its round-three MAC bind the participant id strings
 *   and nothing else. They do not bind the TLS channel, the negotiated cipher, the certificate
 *   fingerprints or the session identifiers. A relay that terminates two TLS connections can run
 *   one PAKE exchange per leg with the same code and still learn nothing -- but without a binding
 *   it can also replay a self-consistent round-one from an earlier session, because a round-one
 *   payload is self-contained and carries the identifier the proof hashes.
 *
 *   Deriving the identifiers from a digest that includes the RFC 9266 channel-binding value means
 *   a payload from another TLS channel cannot name the right participant, and a payload from
 *   another session cannot name the right identifier either. The receiver checks the identifier
 *   against its own derivation *before* the library sees the payload, so replay is refused by
 *   framing rather than hoped for from the cryptography.
 *
 * HONESTY ABOUT THE STATUS OF THIS CONSTRUCTION
 *   This is project-defined and has not been independently cryptographically reviewed. It relies
 *   on the library hashing the participant id string into every proof and into the round-three
 *   MAC, which is a property of that implementation, not a designed channel-binding construction.
 *   The owner accepted that risk explicitly for this personal, experimental application; see
 *   doc/security/owner-risk-waiver-2026-10-19.md.
 *
 * WHY THE TRANSCRIPT DIGEST IS INCLUDED
 *   MST2 already binds both offers, both nonces, both certificate fingerprints, the control and
 *   secure session identifiers, the negotiated TLS protocol and cipher, the limits, the selected
 *   authentication suite and the derived selection. Binding its digest here means this context
 *   transitively covers all of them, and the explicit fields below duplicate the security-relevant
 *   subset so the derivation is readable without chasing another file.
 */

package app.morsecode.core.transfer.session

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The two participant identifiers for one exchange.
 *
 * Both peers derive both strings from the same context, so each can compute its own and its
 * partner's without either being transmitted first. They differ because distinct domain labels
 * are hashed, not because a counter was incremented.
 */
public class JpakeParticipantIds internal constructor(
    public val initiator: String,
    public val responder: String,
) {
    public fun forRole(role: SecurePeerRole): String = when (role) {
        SecurePeerRole.INITIATOR -> initiator
        SecurePeerRole.RESPONDER -> responder
    }

    public fun partnerOf(role: SecurePeerRole): String = forRole(role.opposite)

    override fun toString(): String = "JpakeParticipantIds([redacted])"
}

/**
 * Everything the authentication context binds.
 *
 * [channelBinding] is the RFC 9266 `tls-exporter` value: exporter label
 * `EXPORTER-Channel-Binding`, zero-length context, 32 bytes. It binds the TLS connection only, so
 * the rest of this list is what binds the *session* chosen inside that connection.
 */
public class JpakeBindingInputs(
    channelBinding: ByteArray,
    transcriptDigest: ByteArray,
    codeInstanceId: ByteArray,
    public val formatVersion: Int,
    public val suite: PairingAuthenticationCapability,
    public val initiatorOffer: PairingOfferV2,
    public val responderOffer: PairingOfferV2,
) {
    private val channelBindingValue: ByteArray = requireExact(channelBinding, 32, "channel binding")
    private val transcriptDigestValue: ByteArray = requireExact(transcriptDigest, 32, "transcript digest")
    private val codeInstanceValue: ByteArray = requireExact(codeInstanceId, 16, "code instance id")

    init {
        require(formatVersion in 1..0xFF) { "format version out of range" }
        require(suite != PairingAuthenticationCapability.NONE) { "binding requires a real suite" }
        require(initiatorOffer.role == SecurePeerRole.INITIATOR) { "first offer must be the initiator" }
        require(responderOffer.role == SecurePeerRole.RESPONDER) { "second offer must be the responder" }
    }

    internal fun channelBindingBytes(): ByteArray = channelBindingValue.copyOf()
    internal fun transcriptDigestBytes(): ByteArray = transcriptDigestValue.copyOf()
    internal fun codeInstanceBytes(): ByteArray = codeInstanceValue.copyOf()

    public fun clearSensitive() {
        channelBindingValue.fill(0)
        transcriptDigestValue.fill(0)
        codeInstanceValue.fill(0)
    }

    override fun toString(): String = "JpakeBindingInputs([redacted])"
}

/** Deterministic derivation of the authentication context and the participant identifiers. */
public object JpakeBinding {
    public const val CONTEXT_LABEL: String = "MOR-SEC-AUTHCTX-V1"
    private const val INITIATOR_ID_LABEL = "MOR-SEC-PID-I-V1"
    private const val RESPONDER_ID_LABEL = "MOR-SEC-PID-R-V1"

    /** Prefix keeps identifiers self-describing; total length stays inside the 64-byte bound. */
    public const val INITIATOR_ID_PREFIX: String = "morsec1-i-"
    public const val RESPONDER_ID_PREFIX: String = "morsec1-r-"

    /** Bytes of digest carried in an identifier: 16 bytes render as 32 hex characters. */
    private const val ID_DIGEST_BYTES: Int = 16

    /**
     * Canonical context digest.
     *
     * Fields are written in ascending tag order as (tag, length, value), so the digest is a
     * function of the field *set*. A field silently dropped by one side changes the digest and the
     * identifiers stop matching, which fails closed before any proof is checked.
     */
    public fun authenticationContext(inputs: JpakeBindingInputs): ByteArray {
        val initiator = inputs.initiatorOffer
        val responder = inputs.responderOffer
        val fields = ArrayList<Pair<Int, ByteArray>>()
        fields.add(0x0001 to byteArrayOf(inputs.formatVersion.toByte()))
        fields.add(0x0002 to inputs.channelBindingBytes())
        fields.add(0x0003 to inputs.transcriptDigestBytes())
        fields.add(0x0004 to u16(inputs.suite.wireId))
        fields.add(0x0005 to hexToBytes(initiator.secureSessionId.value))
        fields.add(0x0006 to initiator.controlSessionId.value.toByteArray(Charsets.UTF_8))
        fields.add(0x0007 to initiator.localPeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(0x0008 to initiator.remotePeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(0x0009 to responder.localPeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(0x000A to responder.remotePeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(0x000B to initiator.nonceBytes())
        fields.add(0x000C to responder.nonceBytes())
        fields.add(0x000D to initiator.certificateFingerprintBytes())
        fields.add(0x000E to responder.certificateFingerprintBytes())
        fields.add(0x000F to initiator.tlsProtocol.toByteArray(Charsets.UTF_8))
        fields.add(0x0010 to initiator.tlsCipherSuite.toByteArray(Charsets.UTF_8))
        fields.add(0x0011 to u32(initiator.maxRecordPlaintextBytes))
        fields.add(0x0012 to u32(initiator.maxRecordsPerDirection))
        fields.add(0x0013 to u32(initiator.maxBytesPerDirection))
        fields.add(0x0014 to u32(initiator.sessionLifetimeMillis))
        fields.add(0x0015 to inputs.codeInstanceBytes())

        var size = CONTEXT_LABEL.length + 1
        for ((_, value) in fields) size += 4 + value.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CONTEXT_LABEL.toByteArray(Charsets.UTF_8))
        buffer.put(0.toByte())
        for ((tag, value) in fields) {
            buffer.putShort(tag.toShort())
            buffer.putShort(value.size.toShort())
            buffer.put(value)
        }
        val encoded = buffer.array()
        return try {
            MessageDigest.getInstance("SHA-256").digest(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    /** Derives both identifiers from the context digest. Deterministic on both peers. */
    public fun participantIds(context: ByteArray): JpakeParticipantIds {
        require(context.size == 32) { "context digest must be 32 bytes" }
        return JpakeParticipantIds(
            initiator = INITIATOR_ID_PREFIX + hex(idDigest(INITIATOR_ID_LABEL, context)),
            responder = RESPONDER_ID_PREFIX + hex(idDigest(RESPONDER_ID_LABEL, context)),
        )
    }

    private fun idDigest(label: String, context: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(label.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(context)
        val full = digest.digest()
        return full.copyOf(ID_DIGEST_BYTES)
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return builder.toString()
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

private fun requireExact(value: ByteArray, expected: Int, field: String): ByteArray {
    require(value.size == expected) { "$field must be exactly $expected bytes" }
    return value.copyOf()
}

/** Decodes a 128-bit lowercase hexadecimal session identifier without a BigInteger round trip. */
private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (index in out.indices) {
        out[index] = (
            (Character.digit(hex[index * 2], 16) shl 4) or
                Character.digit(hex[index * 2 + 1], 16)
            ).toByte()
    }
    return out
}
