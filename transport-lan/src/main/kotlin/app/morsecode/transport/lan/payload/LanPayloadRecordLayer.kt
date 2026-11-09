package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureSessionLimits

/*
 * The MPL1 record layer: one MSC1 frame in, one authenticated wire record out.
 *
 * WHAT IT OWNS
 *   Two keyed AEADs, two nonce bases and two sequence counters. Nothing else. There is no socket in
 *   this class, no stream, no file, no clock of its own beyond the one injected for deadline
 *   checks, and no thread. `protect` returns bytes; who carries them, and when, is a decision this
 *   layer deliberately cannot make.
 *
 * WHAT IT DOES NOT ENFORCE
 *   It does not parse the body. The body is one MSC1 frame by contract, and `FrameCodec` is the
 *   only thing that validates a frame; a second parser here would be a second place for the two to
 *   disagree. A zero-length body is therefore structurally fine at this layer and is refused by the
 *   frame decoder the moment composition asks it to parse one.
 *
 * BYTE ORDER AND THE AGE OF A NONCE
 *   The whole 34-byte header is the AEAD's associated data. The nonce is the direction's exporter
 *   IV with the last eight bytes XORed with the sequence, exactly as the control layer builds its
 *   own: same construction, independent material. Two directions have independent IVs and
 *   independent counters, so nonce reuse would require an exporter collision, not a protocol slip.
 *
 * SEQUENCE POLICY, IN FULL
 *   Send: the counter advances only after a complete record has been assembled and is about to be
 *   returned. A failure before that point does not consume the number, because no ciphertext was
 *   produced.
 *   Receive: the counter advances only after the tag verifies, so a forged record cannot advance
 *   the expected sequence and open a window for the record it forged.
 *   Every failure closes the layer permanently. There is no retry path and no state in which a
 *   caller may re-offer a record: a sequence number that was used is used, and a connection that
 *   lost its place cannot find it again.
 *
 * STATUS
 *   Dormant. No production caller constructs this type, no payload byte is written to any socket,
 *   and the payload gate still refuses every session. Experimental, project-defined, not
 *   independently cryptographically reviewed; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

/** Why the payload record layer refused an operation. */
internal enum class LanPayloadLayerFailure {
    /** The layer was already closed, by its owner or by an earlier failure. */
    CLOSED,

    /** The absolute session deadline has passed. */
    EXPIRED,

    /** The monotonic clock went backwards or returned a negative value. */
    CLOCK_INVALID,

    /** One direction has spent its whole record allowance. */
    RECORD_LIMIT_REACHED,

    /** The plaintext offered to `protect` exceeds the fixed body ceiling. */
    PLAINTEXT_ABOVE_MAXIMUM,

    /**
     * The wire record could not be framed: wrong magic, version, session, direction, sequence or
     * length. Raised before any decryption is attempted, and [LanPayloadLayerRefusal.codecReason]
     * carries which of those it was.
     */
    MALFORMED_RECORD,

    /** The tag did not verify, or the plaintext did not match the declared length. */
    AUTHENTICATION_FAILED,
}

/** A refusal, with the codec's own reason when framing is what failed. */
internal class LanPayloadLayerRefusal(
    val failure: LanPayloadLayerFailure,
    val codecReason: LanPayloadRefusal? = null,
) {
    override fun toString(): String =
        "LanPayloadLayerRefusal(failure=$failure, codecReason=$codecReason)"
}

/** The outcome of protecting one body. */
internal sealed interface LanPayloadProtectResult {
    /** One complete wire record: header, ciphertext, tag. The caller writes it contiguously. */
    class Protected(val wireRecord: ByteArray) : LanPayloadProtectResult

    class Refused(val refusal: LanPayloadLayerRefusal) : LanPayloadProtectResult
}

/** The outcome of opening one wire record. */
internal sealed interface LanPayloadUnprotectResult {
    /** The body the sender protected. One MSC1 frame by contract; not parsed here. */
    class Opened(val body: ByteArray) : LanPayloadUnprotectResult

    class Refused(val refusal: LanPayloadLayerRefusal) : LanPayloadUnprotectResult
}

internal class LanPayloadRecordLayer(
    val sessionId: SessionId,
    localRole: SecurePeerRole,
    sendAead: SecureRecordAead,
    receiveAead: SecureRecordAead,
    sendNonceBase: ByteArray,
    receiveNonceBase: ByteArray,
    private val clock: MonotonicClock,
    private val expiresAtElapsedMillis: Long,
    private val maxRecordsPerDirection: Long = LanPayloadRecordFormat.MAX_PAYLOAD_RECORDS_PER_DIRECTION,
) : AutoCloseable {

    private val sender: SecureRecordAead = sendAead
    private val receiver: SecureRecordAead = receiveAead
    private val sendIv: ByteArray = copyNonce(sendNonceBase)
    private val receiveIv: ByteArray = copyNonce(receiveNonceBase)

    private val sendDirection: SecureRecordDirection = SecureRecordLayer.directionFrom(localRole)
    private val receiveDirection: SecureRecordDirection = sendDirection.opposite

    private var lastNowElapsedMillis: Long = -1L
    private var sendSequence: Long = 0L
    private var receiveSequence: Long = 0L
    private var isClosed: Boolean = false

    init {
        require(maxRecordsPerDirection > 0L) { "the payload record ceiling must be positive" }
    }

    /** True while this layer can still be used. A closed layer never reopens. */
    @Synchronized
    fun isOpen(): Boolean = !isClosed

    /** The next sequence this side will send, for tests and for diagnostics. Never a secret. */
    @Synchronized
    fun nextSendSequence(): Long = sendSequence

    /**
     * Seals one body into one complete MPL1 wire record.
     *
     * The returned array is the record and nothing else: a caller that splits it, delays half of it
     * or interleaves another record with it has produced a stream no peer can parse, and no method
     * here can help with that. It must be written contiguously or not at all.
     */
    @Synchronized
    fun protect(msc1FrameBytes: ByteArray): LanPayloadProtectResult {
        if (isClosed) return refuseProtect(LanPayloadLayerFailure.CLOSED)
        if (!clockIsUsable()) return failProtect(LanPayloadLayerFailure.CLOCK_INVALID)
        if (expired()) return failProtect(LanPayloadLayerFailure.EXPIRED)
        if (msc1FrameBytes.size > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) {
            return failProtect(LanPayloadLayerFailure.PLAINTEXT_ABOVE_MAXIMUM)
        }
        if (sendSequence >= maxRecordsPerDirection) {
            return failProtect(LanPayloadLayerFailure.RECORD_LIMIT_REACHED)
        }
        val header = try {
            LanPayloadRecordCodec.encodeHeader(sessionId, sendDirection, sendSequence, msc1FrameBytes.size)
        } catch (_: RuntimeException) {
            return failProtect(LanPayloadLayerFailure.MALFORMED_RECORD)
        }
        val nonce = nonceFor(sendIv, sendSequence)
        val plaintext = msc1FrameBytes.copyOf()
        val ciphertext = try {
            sender.encrypt(nonce, header, plaintext)
        } catch (_: Exception) {
            null
        } finally {
            nonce.fill(0)
            plaintext.fill(0)
        }
        if (ciphertext == null ||
            ciphertext.size != msc1FrameBytes.size + LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES
        ) {
            ciphertext?.fill(0)
            header.fill(0)
            return failProtect(LanPayloadLayerFailure.AUTHENTICATION_FAILED)
        }
        val record = ByteArray(header.size + ciphertext.size)
        header.copyInto(record, 0)
        ciphertext.copyInto(record, header.size)
        header.fill(0)
        ciphertext.fill(0)
        sendSequence++
        return LanPayloadProtectResult.Protected(record)
    }

    /**
     * Opens one complete MPL1 wire record.
     *
     * Framing is checked before any ciphertext is touched: magic, version, session, direction,
     * expected sequence and a length that matches the record exactly. Only then is the tag checked,
     * and only a record that passes both advances the receive counter.
     */
    @Synchronized
    fun unprotect(wireRecord: ByteArray): LanPayloadUnprotectResult {
        if (isClosed) return refuseUnprotect(LanPayloadLayerFailure.CLOSED)
        if (!clockIsUsable()) return failUnprotect(LanPayloadLayerFailure.CLOCK_INVALID)
        if (expired()) return failUnprotect(LanPayloadLayerFailure.EXPIRED)
        // Bounded before anything is copied out of the caller's array.
        if (wireRecord.size > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES) {
            return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD)
        }
        val minimum = LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES +
            LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES
        if (wireRecord.size < minimum) {
            return failUnprotect(
                LanPayloadLayerFailure.MALFORMED_RECORD,
                LanPayloadRefusal.TRUNCATED,
            )
        }
        if (receiveSequence >= maxRecordsPerDirection) {
            return failUnprotect(LanPayloadLayerFailure.RECORD_LIMIT_REACHED)
        }
        val headerBytes = wireRecord.copyOfRange(0, LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
        val headerResult = try {
            LanPayloadRecordCodec.decodeHeader(
                headerBytes,
                sessionId,
                receiveDirection,
                receiveSequence,
            )
        } catch (_: RuntimeException) {
            headerBytes.fill(0)
            return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD)
        }
        if (headerResult is LanPayloadHeaderResult.Refused) {
            headerBytes.fill(0)
            return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD, headerResult.reason)
        }
        val declaredLength = (headerResult as LanPayloadHeaderResult.Decoded).header.declaredLength
        val expectedSize = LanPayloadRecordCodec.wireSizeFor(declaredLength.toLong())
            ?: run {
                headerBytes.fill(0)
                return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.LENGTH_ABOVE_MAXIMUM)
            }
        if (wireRecord.size.toLong() < expectedSize) {
            headerBytes.fill(0)
            return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.LENGTH_INCONSISTENT)
        }
        if (wireRecord.size.toLong() > expectedSize) {
            headerBytes.fill(0)
            return failUnprotect(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.TRAILING_BYTES)
        }
        val ciphertext = wireRecord.copyOfRange(
            LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES,
            wireRecord.size,
        )
        val nonce = nonceFor(receiveIv, receiveSequence)
        val plaintext = try {
            receiver.decrypt(nonce, headerBytes, ciphertext)
        } catch (_: Exception) {
            null
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            headerBytes.fill(0)
        }
        if (plaintext == null || plaintext.size != declaredLength) {
            plaintext?.fill(0)
            return failUnprotect(LanPayloadLayerFailure.AUTHENTICATION_FAILED)
        }
        receiveSequence++
        return LanPayloadUnprotectResult.Opened(plaintext)
    }

    /**
     * Destroys the keys and closes the AEADs. Idempotent, and every later call refuses.
     *
     * There is no "reopen" and no reset: a layer that has been closed has left the protocol.
     */
    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        closeQuietly(sender)
        closeQuietly(receiver)
        sendIv.fill(0)
        receiveIv.fill(0)
    }

    override fun toString(): String =
        "LanPayloadRecordLayer(session=[redacted], sendSequence=$sendSequence, " +
            "receiveSequence=$receiveSequence, open=${!isClosed})"

    private fun clockIsUsable(): Boolean {
        val now = try {
            clock.nowMillis()
        } catch (_: RuntimeException) {
            return false
        }
        if (now < 0L || (lastNowElapsedMillis >= 0L && now < lastNowElapsedMillis)) return false
        lastNowElapsedMillis = now
        return true
    }

    private fun expired(): Boolean {
        val now = lastNowElapsedMillis
        return now < 0L || now >= expiresAtElapsedMillis
    }

    private fun refuseProtect(failure: LanPayloadLayerFailure): LanPayloadProtectResult =
        LanPayloadProtectResult.Refused(LanPayloadLayerRefusal(failure))

    private fun refuseUnprotect(failure: LanPayloadLayerFailure): LanPayloadUnprotectResult =
        LanPayloadUnprotectResult.Refused(LanPayloadLayerRefusal(failure))

    /** A failure is terminal here, in both directions, for both operations. */
    private fun failProtect(failure: LanPayloadLayerFailure): LanPayloadProtectResult {
        val refusal = LanPayloadLayerRefusal(failure)
        close()
        return LanPayloadProtectResult.Refused(refusal)
    }

    private fun failUnprotect(
        failure: LanPayloadLayerFailure,
        codecReason: LanPayloadRefusal? = null,
    ): LanPayloadUnprotectResult {
        val refusal = LanPayloadLayerRefusal(failure, codecReason)
        close()
        return LanPayloadUnprotectResult.Refused(refusal)
    }

    private fun copyNonce(value: ByteArray): ByteArray {
        require(value.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) {
            "the payload nonce base is ${SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM} bytes"
        }
        return value.copyOf()
    }

    private fun nonceFor(base: ByteArray, sequence: Long): ByteArray {
        val nonce = base.copyOf()
        for (index in 0 until Long.SIZE_BYTES) {
            val shift = (Long.SIZE_BYTES - 1 - index) * 8
            val sequenceByte = ((sequence ushr shift) and 0xFF).toByte()
            val nonceOffset = nonce.size - Long.SIZE_BYTES + index
            nonce[nonceOffset] = (nonce[nonceOffset].toInt() xor sequenceByte.toInt()).toByte()
        }
        return nonce
    }

    private fun closeQuietly(aead: SecureRecordAead) {
        try {
            aead.close()
        } catch (_: RuntimeException) {
            // Key wiping is best effort; a provider that fails to close is still closed to us.
        }
    }
}
