package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.transport.lan.security.pairing.JcaAesGcmAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The MPL1 AEAD layer: fixed wire vectors, one refusal per test, and the two properties that make
 * it a record layer rather than a cipher wrapper.
 *
 * The vectors are produced from the published layout and an independent AES-GCM implementation, not
 * from this code. A layer that round-trips with itself still proves nothing about the bytes on the
 * wire; these hex strings do.
 *
 * The last group is the argument for the labels. Two AEADs with the same key and the same nonce
 * produce the same ciphertext body whatever their associated data is -- only the tag moves. That is
 * why the payload key schedule spends four exporter labels, and the test that shows it is here so
 * nobody has to take the sentence on trust.
 *
 * Nothing in this file opens a socket. The layer has none to open.
 */
class LanPayloadRecordLayerTest {

    // --- fixed vectors ---------------------------------------------------------------------------

    @Test fun `the first record is these exact bytes`() {
        val record = protected(layer(), VECTOR_BODY)
        assertEquals(66, record.size)
        assertEquals(
            "4d504c31010123456789abcdef0123456789abcdef01000000000000000000000010" +
                "747925fb403d9cd3f8d631329c48198130e43846a7dd5dbe9adaa32aadc1b3dd",
            hex(record),
        )
    }

    @Test fun `the second record advances the nonce and nothing else`() {
        val sending = layer()
        protected(sending, VECTOR_BODY)
        val second = protected(sending, VECTOR_BODY)
        assertEquals(
            "4d504c31010123456789abcdef0123456789abcdef01000000000000000100000010" +
                "a15429f4442776cc3ccb9f800b0f6f0f578fbbe54d67ef8cd6ab8f6b72379efc",
            hex(second),
        )
    }

    @Test fun `a fixed record opens on the peer that mirrors it`() {
        val record = hexBytes(
            "4d504c31010123456789abcdef0123456789abcdef01000000000000000000000010" +
                "747925fb403d9cd3f8d631329c48198130e43846a7dd5dbe9adaa32aadc1b3dd",
        )
        assertArrayEquals(VECTOR_BODY, opened(receivingLayer(), record))
    }

    // --- sequences -------------------------------------------------------------------------------

    @Test fun `the send sequence starts at zero and advances by one per record`() {
        val sending = layer()
        assertEquals(0L, sending.nextSendSequence())
        protected(sending, ByteArray(1))
        assertEquals(1L, sending.nextSendSequence())
        protected(sending, ByteArray(1))
        assertEquals(2L, sending.nextSendSequence())
    }

    @Test fun `a replayed record is refused`() {
        val sending = layer()
        val peer = receivingLayer()
        val first = protected(sending, ByteArray(8) { 1 })
        opened(peer, first)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.UNEXPECTED_SEQUENCE) {
            peer.unprotect(first)
        }
        assertFalse("a refused record closes the layer", peer.isOpen())
    }

    @Test fun `a skipped sequence is refused`() {
        val sending = layer()
        val peer = receivingLayer()
        protected(sending, ByteArray(8) { 1 })
        val second = protected(sending, ByteArray(8) { 2 })
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.UNEXPECTED_SEQUENCE) {
            peer.unprotect(second)
        }
    }

    @Test fun `the receive sequence advances only after the tag verifies`() {
        val sending = layer()
        val peer = receivingLayer()
        val first = protected(sending, ByteArray(8) { 1 })
        val forged = first.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertRefused(LanPayloadLayerFailure.AUTHENTICATION_FAILED, null) { peer.unprotect(forged) }
        // The forged record did not advance anything: the layer is closed, not moved on.
        assertFalse(peer.isOpen())
    }

    // --- framing, checked before any ciphertext is touched ----------------------------------------

    @Test fun `a record from another session is refused`() {
        val record = protected(layer(), ByteArray(4))
        val foreign = layer(sessionId = SessionId("fedcba9876543210fedcba9876543210"), role = SecurePeerRole.RESPONDER)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.SESSION_MISMATCH) {
            foreign.unprotect(record)
        }
    }

    @Test fun `a reflected record is refused`() {
        val record = protected(layer(), ByteArray(4))
        // A second initiator expects responder-to-initiator traffic, so this record travels the
        // wrong way and must be refused as a direction mismatch rather than decrypted.
        val reflecting = layer(role = SecurePeerRole.INITIATOR)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.DIRECTION_MISMATCH) {
            reflecting.unprotect(record)
        }
    }

    @Test fun `a corrupted magic is refused before decryption`() {
        val record = protected(layer(), ByteArray(4))
        record[1] = (record[1].toInt() xor 0x01).toByte()
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.WRONG_MAGIC) {
            receivingLayer().unprotect(record)
        }
    }

    @Test fun `an unsupported version is refused before decryption`() {
        val record = protected(layer(), ByteArray(4))
        record[LanPayloadRecordFormat.OFF_VERSION] = 2.toByte()
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.UNSUPPORTED_VERSION) {
            receivingLayer().unprotect(record)
        }
    }

    @Test fun `a declared length that does not match the record is refused`() {
        val overstated = protected(layer(), ByteArray(4)).copyOf()
        writeDeclaredLength(overstated, 8L)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.LENGTH_INCONSISTENT) {
            receivingLayer().unprotect(overstated)
        }
    }

    @Test fun `a declared length above the maximum is refused`() {
        val record = protected(layer(), ByteArray(4))
        writeDeclaredLength(record, 0xFFFF_FFFFL)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.LENGTH_ABOVE_MAXIMUM) {
            receivingLayer().unprotect(record)
        }
    }

    @Test fun `a trailing byte is refused`() {
        val record = protected(layer(), ByteArray(4))
        val padded = ByteArray(record.size + 1)
        record.copyInto(padded)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.TRAILING_BYTES) {
            receivingLayer().unprotect(padded)
        }
    }

    @Test fun `a record smaller than a header and a tag is refused`() {
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, LanPayloadRefusal.TRUNCATED) {
            receivingLayer().unprotect(ByteArray(49))
        }
    }

    @Test fun `a record above the wire maximum is refused before any field is read`() {
        val oversized = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES + 1)
        assertRefused(LanPayloadLayerFailure.MALFORMED_RECORD, null) {
            receivingLayer().unprotect(oversized)
        }
    }

    // --- authentication --------------------------------------------------------------------------

    @Test fun `a flipped ciphertext bit closes the layer`() {
        val record = protected(layer(), ByteArray(16) { 7 })
        record[LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES + 3] =
            (record[LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES + 3].toInt() xor 0x40).toByte()
        val peer = receivingLayer()
        assertRefused(LanPayloadLayerFailure.AUTHENTICATION_FAILED, null) { peer.unprotect(record) }
        assertFalse(peer.isOpen())
    }

    @Test fun `a flipped tag bit closes the layer`() {
        val record = protected(layer(), ByteArray(16) { 7 })
        record[record.size - 1] = (record[record.size - 1].toInt() xor 0x01).toByte()
        val peer = receivingLayer()
        assertRefused(LanPayloadLayerFailure.AUTHENTICATION_FAILED, null) { peer.unprotect(record) }
        assertFalse("a tag failure is terminal", peer.isOpen())
        // And a terminal layer stays terminal: the record is not retried.
        assertRefused(LanPayloadLayerFailure.CLOSED, null) { peer.unprotect(record) }
    }

    // --- limits ----------------------------------------------------------------------------------

    @Test fun `the maximum body produces the maximum record`() {
        val body = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) { (it and 0xFF).toByte() }
        val record = protected(layer(), body)
        assertEquals(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES, record.size)
        assertEquals(65_822, record.size)
        assertArrayEquals(body, opened(receivingLayer(), record))
    }

    @Test fun `one byte above the maximum is refused and closes the layer`() {
        val sending = layer()
        val tooBig = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES + 1)
        assertRefused(LanPayloadLayerFailure.PLAINTEXT_ABOVE_MAXIMUM, null) { sending.protect(tooBig) }
        assertFalse("an oversized body is a terminal local error", sending.isOpen())
    }

    @Test fun `one direction cannot spend more records than its ceiling`() {
        val sending = layer(maxRecordsPerDirection = 2L)
        protected(sending, ByteArray(1))
        protected(sending, ByteArray(1))
        assertRefused(LanPayloadLayerFailure.RECORD_LIMIT_REACHED, null) { sending.protect(ByteArray(1)) }
        assertFalse(sending.isOpen())
    }

    @Test fun `the receive ceiling is enforced before the tag is checked`() {
        val sending = layer(maxRecordsPerDirection = 3L)
        val peer = receivingLayer(maxRecordsPerDirection = 2L)
        val first = protected(sending, ByteArray(1))
        val second = protected(sending, ByteArray(1))
        val third = protected(sending, ByteArray(1))
        opened(peer, first)
        opened(peer, second)
        assertRefused(LanPayloadLayerFailure.RECORD_LIMIT_REACHED, null) { peer.unprotect(third) }
    }

    @Test fun `an expired session is refused`() {
        var now = 1_000L
        val sending = layer(clock = MonotonicClock { now }, expiresAtElapsedMillis = 2_000L)
        protected(sending, ByteArray(1))
        now = 2_000L
        assertRefused(LanPayloadLayerFailure.EXPIRED, null) { sending.protect(ByteArray(1)) }
        assertFalse(sending.isOpen())
    }

    @Test fun `a clock that goes backwards is refused`() {
        var now = 1_000L
        val sending = layer(clock = MonotonicClock { now }, expiresAtElapsedMillis = Long.MAX_VALUE)
        protected(sending, ByteArray(1))
        now = 999L
        assertRefused(LanPayloadLayerFailure.CLOCK_INVALID, null) { sending.protect(ByteArray(1)) }
    }

    @Test fun `a closed layer refuses both operations`() {
        val sending = layer()
        sending.close()
        assertFalse(sending.isOpen())
        assertRefused(LanPayloadLayerFailure.CLOSED, null) { sending.protect(ByteArray(1)) }
        assertRefused(LanPayloadLayerFailure.CLOSED, null) { sending.unprotect(protected(layer(), ByteArray(1))) }
        sending.close()
        assertFalse(sending.isOpen())
    }

    // --- the layers meet, and stay apart ---------------------------------------------------------

    @Test fun `an empty body is a valid envelope and an invalid frame`() {
        val sending = layer()
        val peer = receivingLayer()
        val record = protected(sending, ByteArray(0))
        assertEquals(50, record.size)
        assertEquals(0, opened(peer, record).size)
        // The envelope accepted it; the frame decoder is the layer that refuses it, and it is not
        // this layer's job to duplicate that judgement.
        assertFalse(FrameCodec.decode(ByteArray(0)) is FrameDecodeResult.Success)
    }

    @Test fun `payload keys make the same plaintext a different ciphertext than control`() {
        val plaintext = ByteArray(SecureSessionLimits.KEY_CONFIRMATION_BYTES) { (it + 3).toByte() }
        val controlKey = ByteArray(16) { 0x31 }
        val payloadKey = ByteArray(16) { 0x32 }
        val iv = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 0x41 }

        val control = SecureRecordLayer(
            sessionId = SESSION_ID,
            localRole = SecurePeerRole.INITIATOR,
            sendAead = JcaAesGcmAead(controlKey.copyOf()),
            receiveAead = JcaAesGcmAead(controlKey.copyOf()),
            sendNonceBase = iv.copyOf(),
            receiveNonceBase = iv.copyOf(),
            clock = MonotonicClock { 0L },
        )
        val controlFrame = (control.protect(SecureRecordType.KEY_CONFIRMATION, plaintext)
            as SecureRecordResult.Encoded).frameBytes()
        val payloadRecord = protected(
            layer(sendKey = payloadKey, sendIv = iv),
            plaintext,
        )
        assertNotEquals(
            "different labels must give different bytes for identical plaintext",
            hex(controlFrame),
            hex(payloadRecord),
        )
        control.close()
    }

    @Test fun `aad alone is not key separation, which is why the labels exist`() {
        val plaintext = ByteArray(SecureSessionLimits.KEY_CONFIRMATION_BYTES) { (it + 3).toByte() }
        val sharedKey = ByteArray(16) { 0x33 }
        val iv = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 0x42 }

        val control = SecureRecordLayer(
            sessionId = SESSION_ID,
            localRole = SecurePeerRole.INITIATOR,
            sendAead = JcaAesGcmAead(sharedKey.copyOf()),
            receiveAead = JcaAesGcmAead(sharedKey.copyOf()),
            sendNonceBase = iv.copyOf(),
            receiveNonceBase = iv.copyOf(),
            clock = MonotonicClock { 0L },
        )
        val controlBytes = (control.protect(SecureRecordType.KEY_CONFIRMATION, plaintext)
            as SecureRecordResult.Encoded).frameBytes()
        val payloadBytes = protected(layer(sendKey = sharedKey, sendIv = iv), plaintext)
        control.close()

        // GCM is a stream cipher over the plaintext: the body does not depend on the AAD at all.
        // Same key, same nonce, same plaintext -- the ciphertext is identical even though the two
        // headers differ in length and content, and only the tag moves.
        // The control header is 33 bytes and the payload header is 34; the bodies start after them.
        val controlBody = controlBytes.copyOfRange(33, 33 + plaintext.size)
        val payloadBody = payloadBytes.copyOfRange(34, 34 + plaintext.size)
        assertArrayEquals(
            "the associated data does not make the ciphertext different",
            controlBody,
            payloadBody,
        )
        val controlTag = controlBytes.copyOfRange(controlBytes.size - 16, controlBytes.size)
        val payloadTag = payloadBytes.copyOfRange(payloadBytes.size - 16, payloadBytes.size)
        assertFalse("the tag does move, because the AAD covers the header", controlTag.contentEquals(payloadTag))
    }

    @Test fun `the layer prints no key and no session identifier`() {
        val text = layer().toString()
        assertTrue("the layer must be redacted: $text", text.contains("[redacted]"))
        assertFalse(text.contains(SESSION_HEX))
        assertFalse(text.contains("000102"))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun layer(
        role: SecurePeerRole = SecurePeerRole.INITIATOR,
        sessionId: SessionId = SESSION_ID,
        sendKey: ByteArray = KEY_C2S,
        sendIv: ByteArray = VECTOR_IV,
        receiveKey: ByteArray = KEY_S2C,
        receiveIv: ByteArray = VECTOR_IV,
        clock: MonotonicClock = MonotonicClock { 0L },
        expiresAtElapsedMillis: Long = Long.MAX_VALUE,
        maxRecordsPerDirection: Long = LanPayloadRecordFormat.MAX_PAYLOAD_RECORDS_PER_DIRECTION,
    ): LanPayloadRecordLayer = LanPayloadRecordLayer(
        sessionId = sessionId,
        localRole = role,
        sendAead = JcaAesGcmAead(sendKey.copyOf()),
        receiveAead = JcaAesGcmAead(receiveKey.copyOf()),
        sendNonceBase = sendIv.copyOf(),
        receiveNonceBase = receiveIv.copyOf(),
        clock = clock,
        expiresAtElapsedMillis = expiresAtElapsedMillis,
        maxRecordsPerDirection = maxRecordsPerDirection,
    )

    /** The mirror of [layer] with no role: a responder that receives what an initiator sends. */
    private fun receivingLayer(
        maxRecordsPerDirection: Long = LanPayloadRecordFormat.MAX_PAYLOAD_RECORDS_PER_DIRECTION,
    ): LanPayloadRecordLayer = layer(
        role = SecurePeerRole.RESPONDER,
        sendKey = KEY_S2C,
        receiveKey = KEY_C2S,
        maxRecordsPerDirection = maxRecordsPerDirection,
    )

    private fun protected(layer: LanPayloadRecordLayer, body: ByteArray): ByteArray {
        val result = layer.protect(body)
        assertTrue("expected a protected record, got $result", result is LanPayloadProtectResult.Protected)
        return (result as LanPayloadProtectResult.Protected).wireRecord
    }

    private fun opened(layer: LanPayloadRecordLayer, record: ByteArray): ByteArray {
        val result = layer.unprotect(record)
        assertTrue("expected an opened record, got $result", result is LanPayloadUnprotectResult.Opened)
        return (result as LanPayloadUnprotectResult.Opened).body
    }

    private fun assertRefused(
        failure: LanPayloadLayerFailure,
        codecReason: LanPayloadRefusal?,
        block: () -> Any,
    ) {
        val result = block()
        val refusal = when (result) {
            is LanPayloadProtectResult.Refused -> result.refusal
            is LanPayloadUnprotectResult.Refused -> result.refusal
            else -> throw AssertionError("expected a refusal, got $result")
        }
        assertEquals("wrong failure: $refusal", failure, refusal.failure)
        assertEquals("wrong codec reason: $refusal", codecReason, refusal.codecReason)
    }

    private fun writeDeclaredLength(record: ByteArray, declared: Long) {
        for (index in 0 until LanPayloadRecordFormat.DECLARED_LENGTH_BYTES) {
            val shift = (LanPayloadRecordFormat.DECLARED_LENGTH_BYTES - 1 - index) * 8
            record[LanPayloadRecordFormat.OFF_DECLARED_LENGTH + index] =
                ((declared ushr shift) and 0xFF).toByte()
        }
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[value ushr 4])
            builder.append("0123456789abcdef"[value and 0x0F])
        }
        return builder.toString()
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private companion object {
        val SESSION_HEX: String = "0123456789abcdef0123456789abcdef"
        val SESSION_ID: SessionId = SessionId(SESSION_HEX)
        val VECTOR_BODY: ByteArray = "MPL1-vector-body".toByteArray(Charsets.US_ASCII)
        val KEY_C2S: ByteArray = ByteArray(16) { (0x00 + it).toByte() }
        val KEY_S2C: ByteArray = ByteArray(16) { (0x80 + it).toByte() }
        val VECTOR_IV: ByteArray = byteArrayOf(
            0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B,
        )
    }
}
