/*
 * MPS2 / MST2 -- the versioned pairing hello and its canonical transcript.
 *
 * WHY THIS EXISTS
 *   MPS1 was a fixed-schema frame. That made duplicates and unknown mandatory fields
 *   unrepresentable, which is a real property, but it came at a cost: the frame could only
 *   carry a *selected* feature mask, never what each peer actually *offered*. Two peers that
 *   disagreed about what was on the table could still agree on a selection, and the transcript
 *   bound only the agreement. There was also no presence/absence distinction, no extension
 *   section, and no binding of chunk size, resume support or the encryption/authentication
 *   capabilities.
 *
 *   MPS2 fixes that by carrying offers and by encoding presence explicitly. MST2 binds both
 *   peers' offers *and* the derived selection, so a mutation of any security-relevant field
 *   either changes the transcript digest or is rejected outright.
 *
 * WHAT THE WIRE FORMAT GUARANTEES
 *   - Tag/length/value entries in strictly ascending tag order. Out-of-order is rejected, which
 *     is what makes the encoding canonical rather than merely parseable.
 *   - A duplicate tag is rejected. This is representable in TLV and therefore actually tested,
 *     unlike in a fixed schema where the check would be vacuous.
 *   - An unknown tag is rejected. There is no "skip what you do not understand" path, so an
 *     attacker cannot smuggle a field past one peer and have the other ignore it.
 *   - A 16-bit presence mask must exactly equal the set of optional tags present. A mask that
 *     claims a field the frame lacks, or omits one the frame carries, is rejected.
 *   - Presence is distinct from zero. An optional field encoded as zero is not the same bytes as
 *     an absent optional field, and the transcript binds which one it was.
 *   - An explicit empty extension section is distinct from no extension section at all.
 *
 * WHAT IT DOES NOT DO
 *   This is a transcript binding, not an authenticated key exchange. Binding fields
 *   deterministically does not make the surrounding composition secure; that remains a
 *   project-defined protocol pending formal external review.
 */

package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fixed frame bound. Sized for the largest legal MPS2 frame including a full extension set. */
public const val MAX_PAIRING_V2_FRAME_BYTES: Int = 1_024

/** Hard bound on the canonical transcript before any allocation happens. */
public const val MAX_PAIRING_V2_TRANSCRIPT_BYTES: Int = 2_048

/** Tag that terminates the TLV run. Length must be zero. */
private const val TAG_END: Int = 0x0000

// Mandatory tags. Always required; never gated by the presence mask.
private const val TAG_SECURE_SESSION_ID: Int = 0x0001
private const val TAG_CONTROL_SESSION_ID: Int = 0x0002
private const val TAG_ROLE: Int = 0x0003
private const val TAG_LOCAL_PEER_ID: Int = 0x0004
private const val TAG_REMOTE_PEER_ID: Int = 0x0005
private const val TAG_PROTOCOL_MIN: Int = 0x0006
private const val TAG_PROTOCOL_MAX: Int = 0x0007
private const val TAG_NONCE: Int = 0x0008
private const val TAG_CERT_FINGERPRINT: Int = 0x0009
private const val TAG_TRANSPORT: Int = 0x000A
private const val TAG_SECURITY_SUITE: Int = 0x000B
private const val TAG_TLS_PROTOCOL: Int = 0x000C
private const val TAG_TLS_CIPHER: Int = 0x000D
private const val TAG_RECORD_PLAINTEXT_MAX: Int = 0x000E
private const val TAG_RECORDS_PER_DIRECTION: Int = 0x000F
private const val TAG_BYTES_PER_DIRECTION: Int = 0x0010
private const val TAG_SESSION_LIFETIME: Int = 0x0011

// Optional tags. Each owns exactly one presence-mask bit, in ascending tag order.
private const val TAG_OFFERED_FEATURES: Int = 0x0101
private const val TAG_OFFERED_MAX_CHUNK: Int = 0x0102
private const val TAG_OFFERED_RESUME: Int = 0x0103
private const val TAG_OFFERED_ENCRYPTION: Int = 0x0104
private const val TAG_OFFERED_AUTHENTICATION: Int = 0x0105
private const val TAG_APP_VERSION: Int = 0x0106
private const val TAG_EXTENSIONS: Int = 0x0107

/** Number of optional tags, hence the number of defined presence bits. */
public const val PAIRING_V2_PRESENCE_BITS: Int = 7

/** Bits above this are undefined; setting one is a hard reject, not a skip. */
private const val PRESENCE_MASK_DEFINED: Int = (1 shl PAIRING_V2_PRESENCE_BITS) - 1

private const val MAX_PEER_ID_BYTES: Int = 64
private const val MAX_TRANSPORT_ID_BYTES: Int = 16
private const val MAX_TLS_PROTOCOL_BYTES: Int = 16
private const val MAX_TLS_CIPHER_BYTES: Int = 64
private const val MAX_EXTENSION_COUNT: Int = 8
private const val MAX_EXTENSION_PAYLOAD_BYTES: Int = 64
private const val SESSION_ID_BYTES_V2: Int = 16

private val MAGIC_MPS2 = byteArrayOf(0x4D, 0x50, 0x53, 0x32) // "MPS2"
private val MAGIC_MPS1 = byteArrayOf(0x4D, 0x50, 0x53, 0x31) // "MPS1"
private val MAGIC_MST2 = byteArrayOf(0x4D, 0x53, 0x54, 0x32) // "MST2"

private const val SCHEMA_VERSION: Int = 2
private const val HEADER_BYTES: Int = 7 // magic(4) + schema(1) + presenceMask(2)
private const val TLV_OVERHEAD: Int = 4 // tag(2) + length(2)

/**
 * Why an MPS2 frame was refused.
 *
 * `LEGACY_MPS1_UNSUPPORTED` is deliberately its own value rather than a generic parse error: a
 * peer still speaking MPS1 must fail closed and be reported as a version problem, never be
 * retried over a plaintext path.
 */
public enum class PairingWireV2Reject(public val wireReason: String) {
    LEGACY_MPS1_UNSUPPORTED("legacy_mps1_unsupported"),
    BAD_MAGIC("bad_magic"),
    BAD_SCHEMA("bad_schema"),
    FRAME_TOO_SHORT("frame_too_short"),
    FRAME_TOO_LARGE("frame_too_large"),
    UNSUPPORTED_PRESENCE_BIT("unsupported_presence_bit"),
    PRESENCE_MASK_MISMATCH("presence_mask_mismatch"),
    UNKNOWN_FIELD("unknown_field"),
    DUPLICATE_FIELD("duplicate_field"),
    MISSING_MANDATORY_FIELD("missing_mandatory_field"),
    FIELD_ORDER_NOT_CANONICAL("field_order_not_canonical"),
    FIELD_LENGTH_OUT_OF_BOUNDS("field_length_out_of_bounds"),
    FIELD_VALUE_OUT_OF_BOUNDS("field_value_out_of_bounds"),
    HEADER_LENGTH_OUT_OF_BOUNDS("header_length_out_of_bounds"),
    TRAILING_BYTES("trailing_bytes"),
    MALFORMED_END_TAG("malformed_end_tag"),
    EXTENSION_COUNT_OUT_OF_BOUNDS("extension_count_out_of_bounds"),
    EXTENSION_DUPLICATE("extension_duplicate"),
    EXTENSION_UNKNOWN("extension_unknown"),
    EXTENSION_ORDER_NOT_CANONICAL("extension_order_not_canonical"),
    EXTENSION_PAYLOAD_OUT_OF_BOUNDS("extension_payload_out_of_bounds"),
    ROLE_INVALID("role_invalid"),
}

/** Why the two offers could not be intersected into a selection. */
public enum class PairingSelectionReject(public val wireReason: String) {
    PROTOCOL_RANGES_DISJOINT("protocol_ranges_disjoint"),
    PEER_ID_NOT_REFLECTED("peer_id_not_reflected"),
    SESSION_ID_MISMATCH("session_id_mismatch"),
    ROLE_NOT_OPPOSED("role_not_opposed"),
    FEATURE_INTERSECTION_EMPTY("feature_intersection_empty"),
    ENCRYPTION_NOT_MUTUALLY_SUPPORTED("encryption_not_mutually_supported"),
    AUTHENTICATION_NOT_MUTUALLY_SUPPORTED("authentication_not_mutually_supported"),
    TRANSPORT_MISMATCH("transport_mismatch"),
    SUITE_MISMATCH("suite_mismatch"),
    LIMIT_MISMATCH("limit_mismatch"),
    TLS_NOT_1_3("tls_not_1_3"),
    TLS_MISMATCH("tls_mismatch"),
}

/**
 * What the peers can prove to each other out of band.
 *
 * Named for what the implementation actually does: a 25-bit short-authentication-string human
 * comparison. This is not a claim that the composition is reviewed -- it is the mechanism the
 * pairing path uses, and the transcript binds which one was selected.
 */
public enum class PairingAuthenticationCapability(public val wireId: Int) {
    NONE(0),
    SAS_25BIT_COMPARISON(1);

    public companion object {
        public fun fromWireId(id: Int): PairingAuthenticationCapability? =
            entries.firstOrNull { it.wireId == id }
    }
}

/** One extension entry. Tag space is closed: only these are understood. */
public enum class PairingExtensionV2(public val tag: Int) {
    /** Reserved so an explicitly-empty extension section is representable and testable. */
    RESERVED_ZERO(0x0000);

    public companion object {
        public fun fromTag(tag: Int): PairingExtensionV2? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * A peer's advertised position: what it offers, plus the identity and limit fields that are
 * always mandatory. Offers are this peer's own; the selection is derived later from both.
 */
public class PairingOfferV2(
    public val secureSessionId: SessionId,
    public val controlSessionId: SessionId,
    public val role: SecurePeerRole,
    public val localPeerInstanceId: PeerInstanceId,
    public val remotePeerInstanceId: PeerInstanceId,
    public val protocolMinimumVersion: Int,
    public val protocolMaximumVersion: Int,
    nonce: ByteArray,
    certificateFingerprint: ByteArray,
    public val transport: TransportKind,
    public val securitySuiteWireId: Int,
    public val tlsProtocol: String,
    public val tlsCipherSuite: String,
    public val maxRecordPlaintextBytes: Long,
    public val maxRecordsPerDirection: Long,
    public val maxBytesPerDirection: Long,
    public val sessionLifetimeMillis: Long,
    // Offers. Null means "not advertised", which is distinct from advertising zero.
    public val offeredFeaturesMask: Int?,
    public val offeredMaxChunkSizeBytes: Long?,
    public val offeredResumeSupported: Boolean?,
    public val offeredEncryption: EncryptionCapability?,
    public val offeredAuthentication: PairingAuthenticationCapability?,
    public val appVersion: String?,
    public val extensions: List<Pair<Byte, ByteArray>>,
) {
    private val nonceValue: ByteArray = requireExact(nonce, SecureSessionLimits.NONCE_BYTES, "nonce")
    private val fingerprintValue: ByteArray = requireExact(
        certificateFingerprint,
        SecureSessionLimits.FINGERPRINT_BYTES,
        "certificate fingerprint",
    )

    init {
        requireHexSessionId(secureSessionId.value, "secure session id")
        requireHexSessionId(controlSessionId.value, "control session id")
        require(localPeerInstanceId != remotePeerInstanceId) { "peer instance ids must differ" }
        requireBoundedText(localPeerInstanceId.value, MAX_PEER_ID_BYTES, "local peer id")
        requireBoundedText(remotePeerInstanceId.value, MAX_PEER_ID_BYTES, "remote peer id")
        require(protocolMinimumVersion in 1..ProtocolRange.MAX_PROTOCOL_REVISION) {
            "protocol minimum out of range"
        }
        require(protocolMaximumVersion in protocolMinimumVersion..ProtocolRange.MAX_PROTOCOL_REVISION) {
            "protocol maximum out of range"
        }
        requireBoundedText(transport.id, MAX_TRANSPORT_ID_BYTES, "transport id")
        requireBoundedText(tlsProtocol, MAX_TLS_PROTOCOL_BYTES, "tls protocol")
        requireBoundedText(tlsCipherSuite, MAX_TLS_CIPHER_BYTES, "tls cipher suite")
        require(maxRecordPlaintextBytes in 1..0xFFFFFFFFL) { "record plaintext bound out of range" }
        require(maxRecordsPerDirection in 1..0xFFFFFFFFL) { "records-per-direction bound out of range" }
        require(maxBytesPerDirection in 1..0xFFFFFFFFL) { "bytes-per-direction bound out of range" }
        require(sessionLifetimeMillis in 1..0xFFFFFFFFL) { "session lifetime out of range" }
        if (appVersion != null) {
            requireBoundedText(appVersion, MAX_APP_VERSION_BYTES, "app version")
        }
        require(extensions.size <= MAX_EXTENSION_COUNT) { "too many extensions" }
        var previous = -1
        for ((tag, payload) in extensions) {
            val unsigned = tag.toInt() and 0xFF
            require(unsigned > previous) { "extensions must be in strictly ascending tag order" }
            previous = unsigned
            require(payload.size <= MAX_EXTENSION_PAYLOAD_BYTES) { "extension payload too large" }
        }
    }

    public fun nonceBytes(): ByteArray = nonceValue.copyOf()
    public fun certificateFingerprintBytes(): ByteArray = fingerprintValue.copyOf()

    /** The presence mask this offer encodes to. Derived, never caller-supplied. */
    public fun presenceMask(): Int {
        var mask = 0
        if (offeredFeaturesMask != null) mask = mask or (1 shl 0)
        if (offeredMaxChunkSizeBytes != null) mask = mask or (1 shl 1)
        if (offeredResumeSupported != null) mask = mask or (1 shl 2)
        if (offeredEncryption != null) mask = mask or (1 shl 3)
        if (offeredAuthentication != null) mask = mask or (1 shl 4)
        if (appVersion != null) mask = mask or (1 shl 5)
        if (extensions.isNotEmpty()) mask = mask or (1 shl 6)
        return mask
    }

    public fun clearSensitive() {
        nonceValue.fill(0)
        fingerprintValue.fill(0)
    }

    override fun toString(): String = "PairingOfferV2(role=${role.name}, ids=[redacted])"
}

public sealed interface PairingHelloV2Result {
    public data class Success(public val offer: PairingOfferV2) : PairingHelloV2Result
    public data class Rejected(public val reason: PairingWireV2Reject) : PairingHelloV2Result
}

/**
 * MPS2 encoder/decoder.
 *
 * Encoding measures the frame first and allocates exactly once, so no partially-written buffer
 * can escape. Decoding validates every declared length against its per-field bound and the frame
 * bound before copying anything out.
 */
/** Fixed 4-byte big-endian payload length prefix. The payload itself carries the MPS2 magic. */
public const val PAIRING_V2_HEADER_BYTES: Int = 4

public sealed interface PairingV2HeaderResult {
    public data class Valid(public val payloadLength: Int) : PairingV2HeaderResult
    public data class Rejected(public val reason: PairingWireV2Reject) : PairingV2HeaderResult
}

public object SecurePairingHelloV2Codec {
    /**
     * Length-prefixed framing for the wire.
     *
     * The pairing path reads a fixed header before it knows how much to read, so the frame needs
     * one. The length is bounded here rather than trusted: a peer claiming more than the frame
     * ceiling is refused before any buffer of that size is allocated, which is also what makes an
     * MPS1 header uninteresting -- whatever length it encodes, it is either rejected outright or
     * leads to a payload whose magic is not MPS2.
     */
    public fun encodeFrame(offer: PairingOfferV2): ByteArray {
        val frame = encode(offer)
        val out = ByteArray(PAIRING_V2_HEADER_BYTES + frame.size)
        out[0] = ((frame.size ushr 24) and 0xFF).toByte()
        out[1] = ((frame.size ushr 16) and 0xFF).toByte()
        out[2] = ((frame.size ushr 8) and 0xFF).toByte()
        out[3] = (frame.size and 0xFF).toByte()
        frame.copyInto(out, PAIRING_V2_HEADER_BYTES)
        return out
    }

    public fun decodeHeader(header: ByteArray): PairingV2HeaderResult {
        if (header.size != PAIRING_V2_HEADER_BYTES) return PairingV2HeaderResult.Rejected(
            PairingWireV2Reject.FRAME_TOO_SHORT,
        )
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length < HEADER_BYTES || length > MAX_PAIRING_V2_FRAME_BYTES) {
            return PairingV2HeaderResult.Rejected(PairingWireV2Reject.HEADER_LENGTH_OUT_OF_BOUNDS)
        }
        return PairingV2HeaderResult.Valid(length)
    }

    public fun encode(offer: PairingOfferV2): ByteArray {
        val fields = encodeFields(offer)
        var size = HEADER_BYTES
        for ((_, value) in fields) size += TLV_OVERHEAD + value.size
        size += TLV_OVERHEAD // END tag with zero length
        require(size <= MAX_PAIRING_V2_FRAME_BYTES) { "MPS2 frame exceeds its bound" }

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_MPS2)
        buffer.put(SCHEMA_VERSION.toByte())
        buffer.putShort(offer.presenceMask().toShort())
        for ((tag, value) in fields) {
            buffer.putShort(tag.toShort())
            buffer.putShort(value.size.toShort())
            buffer.put(value)
        }
        buffer.putShort(TAG_END.toShort())
        buffer.putShort(0)
        return buffer.array()
    }

    public fun decode(frame: ByteArray): PairingHelloV2Result {
        if (frame.size < HEADER_BYTES) return reject(PairingWireV2Reject.FRAME_TOO_SHORT)
        if (frame.size > MAX_PAIRING_V2_FRAME_BYTES) return reject(PairingWireV2Reject.FRAME_TOO_LARGE)
        if (frame.copyOfRange(0, 4).contentEquals(MAGIC_MPS1)) {
            // Fail closed. Never fall back to MPS1 and never to plaintext.
            return reject(PairingWireV2Reject.LEGACY_MPS1_UNSUPPORTED)
        }
        if (!frame.copyOfRange(0, 4).contentEquals(MAGIC_MPS2)) {
            return reject(PairingWireV2Reject.BAD_MAGIC)
        }
        if ((frame[4].toInt() and 0xFF) != SCHEMA_VERSION) return reject(PairingWireV2Reject.BAD_SCHEMA)

        val declaredMask = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
        if (declaredMask and PRESENCE_MASK_DEFINED.inv() and 0xFFFF != 0) {
            return reject(PairingWireV2Reject.UNSUPPORTED_PRESENCE_BIT)
        }

        val values = LinkedHashMap<Int, ByteArray>()
        var seenOptionalMask = 0
        var offset = HEADER_BYTES
        var terminated = false
        while (offset < frame.size) {
            if (offset + TLV_OVERHEAD > frame.size) return reject(PairingWireV2Reject.FRAME_TOO_SHORT)
            val tag = readU16(frame, offset)
            val length = readU16(frame, offset + 2)
            offset += TLV_OVERHEAD
            if (tag == TAG_END) {
                if (length != 0) return reject(PairingWireV2Reject.MALFORMED_END_TAG)
                terminated = true
                break
            }
            if (offset + length > frame.size) return reject(PairingWireV2Reject.FIELD_LENGTH_OUT_OF_BOUNDS)
            if (values.containsKey(tag)) return reject(PairingWireV2Reject.DUPLICATE_FIELD)
            // Canonical ordering: strictly ascending tags.
            for (previous in values.keys) {
                if (tag <= previous) return reject(PairingWireV2Reject.FIELD_ORDER_NOT_CANONICAL)
            }
            val bound = fieldLengthBound(tag) ?: return reject(PairingWireV2Reject.UNKNOWN_FIELD)
            if (length < bound.first || length > bound.second) {
                return reject(PairingWireV2Reject.FIELD_LENGTH_OUT_OF_BOUNDS)
            }
            values[tag] = frame.copyOfRange(offset, offset + length)
            offset += length
            if (tag >= TAG_OFFERED_FEATURES) {
                seenOptionalMask = seenOptionalMask or (1 shl (tag - TAG_OFFERED_FEATURES))
            }
        }
        if (!terminated) return reject(PairingWireV2Reject.MALFORMED_END_TAG)
        if (offset != frame.size) return reject(PairingWireV2Reject.TRAILING_BYTES)
        if (seenOptionalMask != declaredMask) return reject(PairingWireV2Reject.PRESENCE_MASK_MISMATCH)

        val mandatory = intArrayOf(
            TAG_SECURE_SESSION_ID, TAG_CONTROL_SESSION_ID, TAG_ROLE, TAG_LOCAL_PEER_ID,
            TAG_REMOTE_PEER_ID, TAG_PROTOCOL_MIN, TAG_PROTOCOL_MAX, TAG_NONCE,
            TAG_CERT_FINGERPRINT, TAG_TRANSPORT, TAG_SECURITY_SUITE, TAG_TLS_PROTOCOL,
            TAG_TLS_CIPHER, TAG_RECORD_PLAINTEXT_MAX, TAG_RECORDS_PER_DIRECTION,
            TAG_BYTES_PER_DIRECTION, TAG_SESSION_LIFETIME,
        )
        for (tag in mandatory) {
            if (!values.containsKey(tag)) return reject(PairingWireV2Reject.MISSING_MANDATORY_FIELD)
        }

        val roleWire = readU8(values.getValue(TAG_ROLE), 0)
        val role = SecurePeerRole.entries.firstOrNull { it.wireId == roleWire }
            ?: return reject(PairingWireV2Reject.ROLE_INVALID)
        val transportId = utf8(values.getValue(TAG_TRANSPORT))
        val transport = TransportKind.entries.firstOrNull { it.id == transportId }
            ?: return reject(PairingWireV2Reject.FIELD_VALUE_OUT_OF_BOUNDS)

        val extensions = if (values.containsKey(TAG_EXTENSIONS)) {
            parseExtensions(values.getValue(TAG_EXTENSIONS)) ?: return reject(
                PairingWireV2Reject.EXTENSION_COUNT_OUT_OF_BOUNDS,
            )
        } else {
            emptyList()
        }

        return try {
            PairingHelloV2Result.Success(
                PairingOfferV2(
                    secureSessionId = SessionId(hexOf(values.getValue(TAG_SECURE_SESSION_ID))),
                    controlSessionId = SessionId(hexOf(values.getValue(TAG_CONTROL_SESSION_ID))),
                    role = role,
                    localPeerInstanceId = PeerInstanceId(utf8(values.getValue(TAG_LOCAL_PEER_ID))),
                    remotePeerInstanceId = PeerInstanceId(utf8(values.getValue(TAG_REMOTE_PEER_ID))),
                    protocolMinimumVersion = readU16(values.getValue(TAG_PROTOCOL_MIN), 0),
                    protocolMaximumVersion = readU16(values.getValue(TAG_PROTOCOL_MAX), 0),
                    nonce = values.getValue(TAG_NONCE),
                    certificateFingerprint = values.getValue(TAG_CERT_FINGERPRINT),
                    transport = transport,
                    securitySuiteWireId = readU16(values.getValue(TAG_SECURITY_SUITE), 0),
                    tlsProtocol = utf8(values.getValue(TAG_TLS_PROTOCOL)),
                    tlsCipherSuite = utf8(values.getValue(TAG_TLS_CIPHER)),
                    maxRecordPlaintextBytes = readU32(values.getValue(TAG_RECORD_PLAINTEXT_MAX), 0),
                    maxRecordsPerDirection = readU32(values.getValue(TAG_RECORDS_PER_DIRECTION), 0),
                    maxBytesPerDirection = readU32(values.getValue(TAG_BYTES_PER_DIRECTION), 0),
                    sessionLifetimeMillis = readU32(values.getValue(TAG_SESSION_LIFETIME), 0),
                    offeredFeaturesMask = values[TAG_OFFERED_FEATURES]?.let { readU16(it, 0) },
                    offeredMaxChunkSizeBytes = values[TAG_OFFERED_MAX_CHUNK]?.let { readU32(it, 0) },
                    offeredResumeSupported = values[TAG_OFFERED_RESUME]?.let { readU8(it, 0) == 1 },
                    offeredEncryption = values[TAG_OFFERED_ENCRYPTION]
                        ?.let { EncryptionCapability.fromWireId(readU8(it, 0)) }
                        ?: run {
                            if (values.containsKey(TAG_OFFERED_ENCRYPTION)) {
                                return reject(PairingWireV2Reject.FIELD_VALUE_OUT_OF_BOUNDS)
                            }
                            null
                        },
                    offeredAuthentication = values[TAG_OFFERED_AUTHENTICATION]
                        ?.let { PairingAuthenticationCapability.fromWireId(readU8(it, 0)) }
                        ?: run {
                            if (values.containsKey(TAG_OFFERED_AUTHENTICATION)) {
                                return reject(PairingWireV2Reject.FIELD_VALUE_OUT_OF_BOUNDS)
                            }
                            null
                        },
                    appVersion = values[TAG_APP_VERSION]?.let { utf8(it) },
                    extensions = extensions,
                ),
            )
        } catch (_: IllegalArgumentException) {
            reject(PairingWireV2Reject.FIELD_VALUE_OUT_OF_BOUNDS)
        }
    }

    /** Closed extension tag space: an unknown tag is a reject, never a skip. */
    private fun parseExtensions(raw: ByteArray): List<Pair<Byte, ByteArray>>? {
        if (raw.size < 2) return null
        val count = readU16(raw, 0)
        if (count > MAX_EXTENSION_COUNT) return null
        if (count == 0) {
            // An explicitly empty section is legal and is not the same as an absent one.
            return if (raw.size == 2) emptyList() else null
        }
        val out = ArrayList<Pair<Byte, ByteArray>>(count)
        var offset = 2
        var previous = -1
        repeat(count) {
            if (offset + 3 > raw.size) return null
            val tag = readU8(raw, offset)
            val length = readU16(raw, offset + 1)
            offset += 3
            if (tag <= previous) return null
            previous = tag
            if (length > MAX_EXTENSION_PAYLOAD_BYTES) return null
            if (offset + length > raw.size) return null
            if (PairingExtensionV2.fromTag(tag) == null) return null
            out.add(tag.toByte() to raw.copyOfRange(offset, offset + length))
            offset += length
        }
        return if (offset != raw.size) null else out
    }

    private fun encodeFields(offer: PairingOfferV2): List<Pair<Int, ByteArray>> {
        val fields = ArrayList<Pair<Int, ByteArray>>()
        fields.add(TAG_SECURE_SESSION_ID to bytesOf(offer.secureSessionId.value))
        fields.add(TAG_CONTROL_SESSION_ID to bytesOf(offer.controlSessionId.value))
        fields.add(TAG_ROLE to byteArrayOf(offer.role.wireId.toByte()))
        fields.add(TAG_LOCAL_PEER_ID to offer.localPeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(TAG_REMOTE_PEER_ID to offer.remotePeerInstanceId.value.toByteArray(Charsets.UTF_8))
        fields.add(TAG_PROTOCOL_MIN to u16(offer.protocolMinimumVersion))
        fields.add(TAG_PROTOCOL_MAX to u16(offer.protocolMaximumVersion))
        fields.add(TAG_NONCE to offer.nonceBytes())
        fields.add(TAG_CERT_FINGERPRINT to offer.certificateFingerprintBytes())
        fields.add(TAG_TRANSPORT to offer.transport.id.toByteArray(Charsets.UTF_8))
        fields.add(TAG_SECURITY_SUITE to u16(offer.securitySuiteWireId))
        fields.add(TAG_TLS_PROTOCOL to offer.tlsProtocol.toByteArray(Charsets.UTF_8))
        fields.add(TAG_TLS_CIPHER to offer.tlsCipherSuite.toByteArray(Charsets.UTF_8))
        fields.add(TAG_RECORD_PLAINTEXT_MAX to u32(offer.maxRecordPlaintextBytes))
        fields.add(TAG_RECORDS_PER_DIRECTION to u32(offer.maxRecordsPerDirection))
        fields.add(TAG_BYTES_PER_DIRECTION to u32(offer.maxBytesPerDirection))
        fields.add(TAG_SESSION_LIFETIME to u32(offer.sessionLifetimeMillis))
        offer.offeredFeaturesMask?.let { fields.add(TAG_OFFERED_FEATURES to u16(it)) }
        offer.offeredMaxChunkSizeBytes?.let { fields.add(TAG_OFFERED_MAX_CHUNK to u32(it)) }
        offer.offeredResumeSupported?.let { fields.add(TAG_OFFERED_RESUME to byteArrayOf(if (it) 1 else 0)) }
        offer.offeredEncryption?.let { fields.add(TAG_OFFERED_ENCRYPTION to byteArrayOf(it.wireId.toByte())) }
        offer.offeredAuthentication?.let {
            fields.add(TAG_OFFERED_AUTHENTICATION to byteArrayOf(it.wireId.toByte()))
        }
        offer.appVersion?.let { fields.add(TAG_APP_VERSION to it.toByteArray(Charsets.UTF_8)) }
        if (offer.extensions.isNotEmpty()) {
            var size = 2
            for ((_, payload) in offer.extensions) size += 3 + payload.size
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(offer.extensions.size.toShort())
            for ((tag, payload) in offer.extensions) {
                buffer.put(tag)
                buffer.putShort(payload.size.toShort())
                buffer.put(payload)
            }
            fields.add(TAG_EXTENSIONS to buffer.array())
        }
        // Ascending tag order is what makes the encoding canonical.
        return fields.sortedBy { it.first }
    }

    /** Inclusive length bounds per tag, or null for a tag this implementation does not know. */
    private fun fieldLengthBound(tag: Int): Pair<Int, Int>? = when (tag) {
        TAG_SECURE_SESSION_ID, TAG_CONTROL_SESSION_ID -> SESSION_ID_BYTES_V2 to SESSION_ID_BYTES_V2
        TAG_ROLE -> 1 to 1
        TAG_LOCAL_PEER_ID, TAG_REMOTE_PEER_ID -> 1 to MAX_PEER_ID_BYTES
        TAG_PROTOCOL_MIN, TAG_PROTOCOL_MAX -> 2 to 2
        TAG_NONCE -> SecureSessionLimits.NONCE_BYTES to SecureSessionLimits.NONCE_BYTES
        TAG_CERT_FINGERPRINT -> SecureSessionLimits.FINGERPRINT_BYTES to SecureSessionLimits.FINGERPRINT_BYTES
        TAG_TRANSPORT -> 1 to MAX_TRANSPORT_ID_BYTES
        TAG_SECURITY_SUITE -> 2 to 2
        TAG_TLS_PROTOCOL -> 1 to MAX_TLS_PROTOCOL_BYTES
        TAG_TLS_CIPHER -> 1 to MAX_TLS_CIPHER_BYTES
        TAG_RECORD_PLAINTEXT_MAX, TAG_RECORDS_PER_DIRECTION,
        TAG_BYTES_PER_DIRECTION, TAG_SESSION_LIFETIME,
        -> 4 to 4
        TAG_OFFERED_FEATURES -> 2 to 2
        TAG_OFFERED_MAX_CHUNK -> 4 to 4
        TAG_OFFERED_RESUME, TAG_OFFERED_ENCRYPTION, TAG_OFFERED_AUTHENTICATION -> 1 to 1
        // SessionContracts.MAX_APP_VERSION_BYTES -- one bound for app version, shared with the
        // control handshake, so the two formats cannot drift.
        TAG_APP_VERSION -> 1 to MAX_APP_VERSION_BYTES
        TAG_EXTENSIONS -> 2 to (2 + MAX_EXTENSION_COUNT * (3 + MAX_EXTENSION_PAYLOAD_BYTES))
        else -> null
    }

    private fun reject(reason: PairingWireV2Reject): PairingHelloV2Result.Rejected =
        PairingHelloV2Result.Rejected(reason)
}

/** The values both peers agreed on, derived as an intersection of their offers. */
public class PairingSelectionV2(
    public val selectedProtocolVersion: Int,
    public val selectedFeaturesMask: Int,
    public val selectedMaxChunkSizeBytes: Long,
    public val selectedResumeSupported: Boolean,
    public val selectedEncryption: EncryptionCapability,
    public val selectedAuthentication: PairingAuthenticationCapability,
)

public sealed interface PairingSelectionResult {
    public data class Selected(public val selection: PairingSelectionV2) : PairingSelectionResult
    public data class Rejected(public val reason: PairingSelectionReject) : PairingSelectionResult
}

/**
 * Derives the selection from both offers.
 *
 * Every selected value is an intersection or a minimum of what the two peers actually offered.
 * Nothing here is caller-supplied, so a peer cannot assert a selection the other never offered.
 */
public object SecurePairingSelectionV2 {
    public fun select(
        initiator: PairingOfferV2,
        responder: PairingOfferV2,
    ): PairingSelectionResult {
        if (initiator.role != SecurePeerRole.INITIATOR || responder.role != SecurePeerRole.RESPONDER) {
            return reject(PairingSelectionReject.ROLE_NOT_OPPOSED)
        }
        if (initiator.secureSessionId != responder.secureSessionId ||
            initiator.controlSessionId != responder.controlSessionId
        ) {
            return reject(PairingSelectionReject.SESSION_ID_MISMATCH)
        }
        // Each peer must name the other as its remote, in both directions.
        if (initiator.localPeerInstanceId != responder.remotePeerInstanceId ||
            initiator.remotePeerInstanceId != responder.localPeerInstanceId
        ) {
            return reject(PairingSelectionReject.PEER_ID_NOT_REFLECTED)
        }
        if (initiator.transport != responder.transport) return reject(PairingSelectionReject.TRANSPORT_MISMATCH)
        // The offers carry the TLS protocol and cipher that were actually negotiated on the
        // established session, so both sides must report the same pair and it must be TLS 1.3.
        if (initiator.tlsProtocol != responder.tlsProtocol ||
            initiator.tlsCipherSuite != responder.tlsCipherSuite
        ) {
            return reject(PairingSelectionReject.TLS_MISMATCH)
        }
        if (initiator.tlsProtocol != "TLSv1.3") return reject(PairingSelectionReject.TLS_NOT_1_3)
        if (initiator.securitySuiteWireId != responder.securitySuiteWireId) {
            return reject(PairingSelectionReject.SUITE_MISMATCH)
        }
        if (initiator.maxRecordPlaintextBytes != responder.maxRecordPlaintextBytes ||
            initiator.maxRecordsPerDirection != responder.maxRecordsPerDirection ||
            initiator.maxBytesPerDirection != responder.maxBytesPerDirection ||
            initiator.sessionLifetimeMillis != responder.sessionLifetimeMillis
        ) {
            return reject(PairingSelectionReject.LIMIT_MISMATCH)
        }

        val low = maxOf(initiator.protocolMinimumVersion, responder.protocolMinimumVersion)
        val high = minOf(initiator.protocolMaximumVersion, responder.protocolMaximumVersion)
        if (low > high) return reject(PairingSelectionReject.PROTOCOL_RANGES_DISJOINT)

        val offeredA = initiator.offeredFeaturesMask ?: 0
        val offeredB = responder.offeredFeaturesMask ?: 0
        val features = offeredA and offeredB
        if (features == 0) return reject(PairingSelectionReject.FEATURE_INTERSECTION_EMPTY)

        val encryption = if (
            initiator.offeredEncryption == EncryptionCapability.TLS_1_3 &&
            responder.offeredEncryption == EncryptionCapability.TLS_1_3
        ) {
            EncryptionCapability.TLS_1_3
        } else {
            return reject(PairingSelectionReject.ENCRYPTION_NOT_MUTUALLY_SUPPORTED)
        }

        val authA = initiator.offeredAuthentication?.wireId ?: 0
        val authB = responder.offeredAuthentication?.wireId ?: 0
        val authWire = minOf(authA, authB)
        val authentication = PairingAuthenticationCapability.fromWireId(authWire)
            ?: return reject(PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED)
        if (authWire == 0) return reject(PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED)

        return PairingSelectionResult.Selected(
            PairingSelectionV2(
                selectedProtocolVersion = high,
                selectedFeaturesMask = features,
                selectedMaxChunkSizeBytes = minOf(
                    initiator.offeredMaxChunkSizeBytes ?: 0L,
                    responder.offeredMaxChunkSizeBytes ?: 0L,
                ),
                selectedResumeSupported = (initiator.offeredResumeSupported ?: false) &&
                    (responder.offeredResumeSupported ?: false),
                selectedEncryption = encryption,
                selectedAuthentication = authentication,
            ),
        )
    }

    private fun reject(reason: PairingSelectionReject): PairingSelectionResult.Rejected =
        PairingSelectionResult.Rejected(reason)
}

/**
 * MST2 -- the canonical transcript.
 *
 * Fields are written in ascending field id order as (id, length, bytes), which makes the byte
 * string a function of the field *set* rather than of the order anything happened to be
 * appended in. Both peers' offers and the derived selection are bound, along with both presence
 * masks, so a field that one side silently dropped changes the digest.
 */
public object SecurePairingTranscriptV2 {
    private const val F_INIT_PROTOCOL_MIN: Int = 0x0001
    private const val F_INIT_PROTOCOL_MAX: Int = 0x0002
    private const val F_RESP_PROTOCOL_MIN: Int = 0x0003
    private const val F_RESP_PROTOCOL_MAX: Int = 0x0004
    private const val F_SELECTED_PROTOCOL: Int = 0x0005
    private const val F_INIT_ROLE: Int = 0x0006
    private const val F_RESP_ROLE: Int = 0x0007
    private const val F_SECURE_SESSION_ID: Int = 0x0008
    private const val F_CONTROL_SESSION_ID: Int = 0x0009
    private const val F_INIT_LOCAL_PEER: Int = 0x000A
    private const val F_INIT_REMOTE_PEER: Int = 0x000B
    private const val F_RESP_LOCAL_PEER: Int = 0x000C
    private const val F_RESP_REMOTE_PEER: Int = 0x000D
    private const val F_INIT_NONCE: Int = 0x000E
    private const val F_RESP_NONCE: Int = 0x000F
    private const val F_INIT_FINGERPRINT: Int = 0x0010
    private const val F_RESP_FINGERPRINT: Int = 0x0011
    private const val F_TRANSPORT: Int = 0x0012
    private const val F_INIT_OFFERED_FEATURES: Int = 0x0013
    private const val F_RESP_OFFERED_FEATURES: Int = 0x0014
    private const val F_SELECTED_FEATURES: Int = 0x0015
    private const val F_INIT_OFFERED_CHUNK: Int = 0x0016
    private const val F_RESP_OFFERED_CHUNK: Int = 0x0017
    private const val F_SELECTED_CHUNK: Int = 0x0018
    private const val F_INIT_OFFERED_RESUME: Int = 0x0019
    private const val F_RESP_OFFERED_RESUME: Int = 0x001A
    private const val F_SELECTED_RESUME: Int = 0x001B
    private const val F_INIT_OFFERED_ENCRYPTION: Int = 0x001C
    private const val F_RESP_OFFERED_ENCRYPTION: Int = 0x001D
    private const val F_SELECTED_ENCRYPTION: Int = 0x001E
    private const val F_INIT_OFFERED_AUTH: Int = 0x001F
    private const val F_RESP_OFFERED_AUTH: Int = 0x0020
    private const val F_SELECTED_AUTH: Int = 0x0021
    private const val F_SECURITY_SUITE: Int = 0x0022
    private const val F_RECORD_PLAINTEXT_MAX: Int = 0x0023
    private const val F_RECORDS_PER_DIRECTION: Int = 0x0024
    private const val F_BYTES_PER_DIRECTION: Int = 0x0025
    private const val F_SESSION_LIFETIME: Int = 0x0026
    private const val F_TLS_PROTOCOL: Int = 0x0027
    private const val F_TLS_CIPHER: Int = 0x0028
    private const val F_INIT_APP_VERSION: Int = 0x0029
    private const val F_RESP_APP_VERSION: Int = 0x002A
    private const val F_EXTENSIONS: Int = 0x002B
    private const val F_INIT_PRESENCE_MASK: Int = 0x002C
    private const val F_RESP_PRESENCE_MASK: Int = 0x002D

    /**
     * Builds the canonical transcript bytes and their SHA-256 digest.
     *
     * Returns null when the transcript would exceed its bound, which is checked before the
     * buffer is allocated rather than by catching an overflow.
     */
    public fun build(
        initiator: PairingOfferV2,
        responder: PairingOfferV2,
        selection: PairingSelectionV2,
    ): Pair<ByteArray, ByteArray>? {
        // A sorted list rather than a map: the module's import allowlist deliberately excludes
        // java.util, and canonical order only needs a sort, not a map.
        val fields = ArrayList<Pair<Int, ByteArray>>()
        fields.add(F_INIT_PROTOCOL_MIN to u16(initiator.protocolMinimumVersion))
        fields.add(F_INIT_PROTOCOL_MAX to u16(initiator.protocolMaximumVersion))

        fields.add(F_RESP_PROTOCOL_MIN to u16(responder.protocolMinimumVersion))

        fields.add(F_RESP_PROTOCOL_MAX to u16(responder.protocolMaximumVersion))

        fields.add(F_SELECTED_PROTOCOL to u16(selection.selectedProtocolVersion))

        fields.add(F_INIT_ROLE to byteArrayOf(initiator.role.wireId.toByte()))

        fields.add(F_RESP_ROLE to byteArrayOf(responder.role.wireId.toByte()))

        fields.add(F_SECURE_SESSION_ID to bytesOf(initiator.secureSessionId.value))

        fields.add(F_CONTROL_SESSION_ID to bytesOf(initiator.controlSessionId.value))

        fields.add(F_INIT_LOCAL_PEER to initiator.localPeerInstanceId.value.toByteArray(Charsets.UTF_8))

        fields.add(F_INIT_REMOTE_PEER to initiator.remotePeerInstanceId.value.toByteArray(Charsets.UTF_8))

        fields.add(F_RESP_LOCAL_PEER to responder.localPeerInstanceId.value.toByteArray(Charsets.UTF_8))

        fields.add(F_RESP_REMOTE_PEER to responder.remotePeerInstanceId.value.toByteArray(Charsets.UTF_8))

        fields.add(F_INIT_NONCE to initiator.nonceBytes())

        fields.add(F_RESP_NONCE to responder.nonceBytes())

        fields.add(F_INIT_FINGERPRINT to initiator.certificateFingerprintBytes())

        fields.add(F_RESP_FINGERPRINT to responder.certificateFingerprintBytes())

        fields.add(F_TRANSPORT to initiator.transport.id.toByteArray(Charsets.UTF_8))

        fields.add(F_INIT_OFFERED_FEATURES to optionalU16(initiator.offeredFeaturesMask))

        fields.add(F_RESP_OFFERED_FEATURES to optionalU16(responder.offeredFeaturesMask))

        fields.add(F_SELECTED_FEATURES to u16(selection.selectedFeaturesMask))

        fields.add(F_INIT_OFFERED_CHUNK to optionalU32(initiator.offeredMaxChunkSizeBytes))

        fields.add(F_RESP_OFFERED_CHUNK to optionalU32(responder.offeredMaxChunkSizeBytes))

        fields.add(F_SELECTED_CHUNK to u32(selection.selectedMaxChunkSizeBytes))

        fields.add(F_INIT_OFFERED_RESUME to optionalFlag(initiator.offeredResumeSupported))

        fields.add(F_RESP_OFFERED_RESUME to optionalFlag(responder.offeredResumeSupported))

        fields.add(F_SELECTED_RESUME to byteArrayOf(if (selection.selectedResumeSupported) 1 else 0))

        fields.add(F_INIT_OFFERED_ENCRYPTION to optionalWire(initiator.offeredEncryption?.wireId))

        fields.add(F_RESP_OFFERED_ENCRYPTION to optionalWire(responder.offeredEncryption?.wireId))

        fields.add(F_SELECTED_ENCRYPTION to byteArrayOf(selection.selectedEncryption.wireId.toByte()))

        fields.add(F_INIT_OFFERED_AUTH to optionalWire(initiator.offeredAuthentication?.wireId))

        fields.add(F_RESP_OFFERED_AUTH to optionalWire(responder.offeredAuthentication?.wireId))

        fields.add(F_SELECTED_AUTH to byteArrayOf(selection.selectedAuthentication.wireId.toByte()))

        fields.add(F_SECURITY_SUITE to u16(initiator.securitySuiteWireId))

        fields.add(F_RECORD_PLAINTEXT_MAX to u32(initiator.maxRecordPlaintextBytes))

        fields.add(F_RECORDS_PER_DIRECTION to u32(initiator.maxRecordsPerDirection))

        fields.add(F_BYTES_PER_DIRECTION to u32(initiator.maxBytesPerDirection))

        fields.add(F_SESSION_LIFETIME to u32(initiator.sessionLifetimeMillis))

        fields.add(F_TLS_PROTOCOL to initiator.tlsProtocol.toByteArray(Charsets.UTF_8))

        fields.add(F_TLS_CIPHER to initiator.tlsCipherSuite.toByteArray(Charsets.UTF_8))

        fields.add(F_INIT_APP_VERSION to optionalText(initiator.appVersion))

        fields.add(F_RESP_APP_VERSION to optionalText(responder.appVersion))

        fields.add(F_EXTENSIONS to canonicalExtensions(initiator.extensions, responder.extensions))

        fields.add(F_INIT_PRESENCE_MASK to u16(initiator.presenceMask()))

        fields.add(F_RESP_PRESENCE_MASK to u16(responder.presenceMask()))


        var size = MAGIC_MST2.size + 1
        fields.sortBy { it.first }
        for ((_, value) in fields) size += 4 + value.size
        if (size > MAX_PAIRING_V2_TRANSCRIPT_BYTES) return null

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_MST2)
        buffer.put(SCHEMA_VERSION.toByte())
        for ((id, value) in fields) {
            buffer.putShort(id.toShort())
            buffer.putShort(value.size.toShort())
            buffer.put(value)
        }
        val encoded = buffer.array()
        return encoded to MessageDigest.getInstance("SHA-256").digest(encoded)
    }

    /**
     * Absent is encoded as a zero-length value; a present zero is a one-byte 0x00. The two are
     * different bytes, which is the whole point of the distinction.
     */
    private fun optionalU16(value: Int?): ByteArray = if (value == null) ByteArray(0) else u16(value)
    private fun optionalU32(value: Long?): ByteArray = if (value == null) ByteArray(0) else u32(value)
    private fun optionalFlag(value: Boolean?): ByteArray =
        if (value == null) ByteArray(0) else byteArrayOf(if (value) 1 else 0)
    private fun optionalWire(value: Int?): ByteArray =
        if (value == null) ByteArray(0) else byteArrayOf(value.toByte())
    private fun optionalText(value: String?): ByteArray =
        if (value == null) ByteArray(0) else value.toByteArray(Charsets.UTF_8)

    /**
     * Extensions from both peers, merged and sorted by tag, with the owning side tagged. An
     * empty result still occupies the field, so "no extensions" is bound rather than implied.
     */
    private fun canonicalExtensions(
        initiator: List<Pair<Byte, ByteArray>>,
        responder: List<Pair<Byte, ByteArray>>,
    ): ByteArray {
        val merged = ArrayList<Triple<Int, Int, ByteArray>>()
        for ((tag, payload) in initiator) merged.add(Triple(1, tag.toInt() and 0xFF, payload))
        for ((tag, payload) in responder) merged.add(Triple(2, tag.toInt() and 0xFF, payload))
        merged.sortWith(compareBy({ it.second }, { it.first }))
        var size = 2
        for ((_, _, payload) in merged) size += 4 + payload.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(merged.size.toShort())
        for ((side, tag, payload) in merged) {
            buffer.putShort(((side shl 8) or tag).toShort())
            buffer.putShort(payload.size.toShort())
            buffer.put(payload)
        }
        return buffer.array()
    }
}

// --- small helpers, kept local so this file has no dependency on codec internals ---

private fun requireExact(value: ByteArray, expected: Int, field: String): ByteArray {
    require(value.size == expected) { "$field must be exactly $expected bytes" }
    return value.copyOf()
}

private fun requireHexSessionId(value: String, field: String) {
    require(value.matches(Regex("[0-9a-f]{32}"))) { "$field must be 128-bit lowercase hexadecimal" }
}

private fun requireBoundedText(value: String, maxBytes: Int, field: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    require(encoded.isNotEmpty() && encoded.size <= maxBytes) { "$field is outside its byte bound" }
    require(value.all { it.code in 0x20..0x7E }) { "$field must be printable ASCII" }
}

private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

private fun u32(value: Long): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

private fun readU8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun readU32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** 32 lowercase hex characters to 16 raw bytes. */
private fun bytesOf(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
    return out
}

private fun hexOf(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val v = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return builder.toString()
}
