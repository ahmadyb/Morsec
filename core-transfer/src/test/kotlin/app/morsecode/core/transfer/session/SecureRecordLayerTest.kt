package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class SecureRecordLayerTest {
    @Test
    public fun aesGcmMatchesNist128BitKnownAnswerVector() {
        val key = ByteArray(16)
        val nonce = ByteArray(12)
        val plaintext = ByteArray(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val output = cipher.doFinal(plaintext)
        try {
            assertEquals(
                "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf",
                output.toHex(),
            )
        } finally {
            key.fill(0)
            nonce.fill(0)
            plaintext.fill(0)
            output.fill(0)
        }
    }

    @Test
    public fun recordsBindSessionRoleDirectionSequenceTypeAndAeadPayload() {
        val pair = makePair()
        val frame = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
        val header = SecureRecordCodec.decodeHeader(
            frame.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES),
            SESSION_ID,
            SecureRecordDirection.INITIATOR_TO_RESPONDER,
        )
        assertNotNull(header)
        assertEquals(0L, header!!.sequenceNumber)
        assertEquals(SecureRecordType.KEY_CONFIRMATION, header.type)
        assertEquals(SecureKeyConfirmationCodec.FRAME_SIZE_BYTES, header.plaintextLength)

        val decoded = pair.responder.unprotect(frame) as SecureRecordResult.Decoded
        val plaintext = decoded.record.payloadBytes()
        val confirmation = SecureKeyConfirmationCodec.decode(plaintext)
        assertNotNull(confirmation)
        assertEquals(SecurePeerRole.INITIATOR, confirmation!!.role)
        confirmation.clearSensitive()
        plaintext.fill(0)
        decoded.record.clearSensitive()

        val ping = encodedFrame(pair.initiator.protect(SecureRecordType.PING, ByteArray(0)))
        val pingHeader = SecureRecordCodec.decodeHeader(
            ping.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES),
            SESSION_ID,
            SecureRecordDirection.INITIATOR_TO_RESPONDER,
        )
        assertNotNull(pingHeader)
        assertEquals(1L, pingHeader!!.sequenceNumber)
        val receivedPing = pair.responder.unprotect(ping) as SecureRecordResult.Decoded
        assertEquals(SecureRecordType.PING, receivedPing.record.type)
        receivedPing.record.clearSensitive()

        frame.fill(0)
        ping.fill(0)
        pair.close()
    }

    @Test
    public fun sessionCloseRecordIsTerminalAndWipesBothDirectionalKeys() {
        val pair = makePair()
        val first = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
        val receivedConfirmation = pair.responder.unprotect(first) as SecureRecordResult.Decoded
        receivedConfirmation.record.clearSensitive()

        val closeFrame = encodedFrame(pair.initiator.protect(SecureRecordType.SESSION_CLOSE, ByteArray(0)))
        assertTrue(pair.clientSend.closed)
        assertTrue(pair.clientReceive.closed)
        val receivedClose = pair.responder.unprotect(closeFrame) as SecureRecordResult.Decoded
        assertEquals(SecureRecordType.SESSION_CLOSE, receivedClose.record.type)
        assertTrue(pair.serverSend.closed)
        assertTrue(pair.serverReceive.closed)
        val subsequent = pair.initiator.protect(SecureRecordType.PING, ByteArray(0)) as SecureRecordResult.Failed
        assertEquals(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, subsequent.failure.code)

        first.fill(0)
        closeFrame.fill(0)
        receivedClose.record.clearSensitive()
        pair.close()
    }

    @Test
    public fun replayReflectionSequenceGapAndTagTamperingCloseTheLayer() {
        run {
            val pair = makePair()
            val frame = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
            assertTrue(pair.responder.unprotect(frame) is SecureRecordResult.Decoded)
            assertTrue(pair.responder.unprotect(frame) is SecureRecordResult.Failed)
            assertTrue(pair.serverReceive.closed)
            frame.fill(0)
            pair.close()
        }
        run {
            val pair = makePair()
            val frame = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
            assertTrue(pair.initiator.unprotect(frame) is SecureRecordResult.Failed)
            assertTrue(pair.clientSend.closed)
            frame.fill(0)
            pair.close()
        }
        run {
            val pair = makePair()
            val first = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
            val second = encodedFrame(pair.initiator.protect(SecureRecordType.PING, ByteArray(0)))
            assertTrue(pair.responder.unprotect(second) is SecureRecordResult.Failed)
            assertTrue(pair.serverReceive.closed)
            first.fill(0)
            second.fill(0)
            pair.close()
        }
        run {
            val pair = makePair()
            val frame = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
            frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
            assertTrue(pair.responder.unprotect(frame) is SecureRecordResult.Failed)
            assertTrue(pair.serverReceive.closed)
            frame.fill(0)
            pair.close()
        }
    }

    @Test
    public fun fixedHeaderRejectsWrongSessionDirectionVersionTypeLengthAndSequence() {
        val valid = SecureRecordCodec.encodeHeader(
            SESSION_ID,
            SecureRecordDirection.INITIATOR_TO_RESPONDER,
            0,
            SecureRecordType.KEY_CONFIRMATION,
            SecureKeyConfirmationCodec.FRAME_SIZE_BYTES,
        )
        assertNotNull(SecureRecordCodec.decodeHeader(valid, SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER))
        assertNull(SecureRecordCodec.decodeHeader(valid, OTHER_SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER))
        assertNull(SecureRecordCodec.decodeHeader(valid, SESSION_ID, SecureRecordDirection.RESPONDER_TO_INITIATOR))
        assertNull(SecureRecordCodec.decodeHeader(
            valid.copyOf().also { it[4] = 2 }, SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER,
        ))
        assertNull(SecureRecordCodec.decodeHeader(
            valid.copyOf().also { it[30] = 99 }, SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER,
        ))
        assertNull(SecureRecordCodec.decodeHeader(
            valid.copyOf().also { it[31] = 0xFF.toByte(); it[32] = 0xFF.toByte() },
            SESSION_ID,
            SecureRecordDirection.INITIATOR_TO_RESPONDER,
        ))
        assertNull(SecureRecordCodec.decodeHeader(
            valid.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)
                    .putLong(22, SecureSessionLimits.MAX_RECORDS_PER_DIRECTION)
            },
            SESSION_ID,
            SecureRecordDirection.INITIATOR_TO_RESPONDER,
        ))
        assertTrue(SecureRecordType.entries.none { it.name.contains("FILE") || it.name.contains("PAYLOAD") })
        valid.fill(0)
    }

    @Test
    public fun firstRecordMustConfirmAndSequenceLimitClosesBeforeNonceWrap() {
        val beforeConfirm = makePair()
        assertTrue(beforeConfirm.initiator.protect(SecureRecordType.PING, ByteArray(0)) is SecureRecordResult.Failed)
        assertTrue(beforeConfirm.clientSend.closed)
        val alreadyClosed = beforeConfirm.initiator.protect(
            SecureRecordType.PING,
            ByteArray(0),
        ) as SecureRecordResult.Failed
        assertEquals(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, alreadyClosed.failure.code)
        beforeConfirm.close()

        val pair = makePair()
        val first = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
        first.fill(0)
        repeat(SecureSessionLimits.MAX_RECORDS_PER_DIRECTION.toInt() - 1) { sequence ->
            val result = pair.initiator.protect(SecureRecordType.PING, ByteArray(0))
            assertTrue("sequence $sequence", result is SecureRecordResult.Encoded)
            (result as SecureRecordResult.Encoded).frameBytes().fill(0)
        }
        assertTrue(pair.initiator.protect(SecureRecordType.PING, ByteArray(0)) is SecureRecordResult.Failed)
        assertTrue(pair.clientSend.closed)
        assertTrue(pair.clientReceive.closed)
        pair.close()
    }

    @Test
    public fun expiryAndMonotonicRollbackCloseBothDirectionalKeys() {
        val pair = makePair()
        val frame = confirmationFrame(pair.initiator, SecurePeerRole.INITIATOR)
        pair.clock.now = 10L + SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS
        assertTrue(pair.initiator.protect(SecureRecordType.PING, ByteArray(0)) is SecureRecordResult.Failed)
        assertTrue(pair.clientSend.closed)
        assertTrue(pair.clientReceive.closed)
        pair.close()
        frame.fill(0)

        val rollback = makePair()
        val rollbackFrame = confirmationFrame(rollback.initiator, SecurePeerRole.INITIATOR)
        rollback.clock.now = -1L
        assertTrue(rollback.initiator.unprotect(rollbackFrame) is SecureRecordResult.Failed)
        assertTrue(rollback.clientSend.closed)
        assertTrue(rollback.clientReceive.closed)
        rollback.close()
        rollbackFrame.fill(0)
    }

    private fun confirmationFrame(layer: SecureRecordLayer, role: SecurePeerRole): ByteArray {
        val digest = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 1).toByte() }
        val message = SecureKeyConfirmation(role, digest)
        digest.fill(0)
        val payload = try {
            SecureKeyConfirmationCodec.encode(message)
        } finally {
            message.clearSensitive()
        }
        return try {
            encodedFrame(layer.protect(SecureRecordType.KEY_CONFIRMATION, payload))
        } finally {
            payload.fill(0)
        }
    }

    private fun encodedFrame(result: SecureRecordResult): ByteArray =
        (result as? SecureRecordResult.Encoded)?.frameBytes() ?: error("record encoding failed")

    @Synchronized
    private fun makePair(): PairOfLayers {
        val seed = nextSeed++
        val clientKey = ByteArray(SecureSessionLimits.AES_KEY_BYTES) { (seed + it).toByte() }
        val serverKey = ByteArray(SecureSessionLimits.AES_KEY_BYTES) { (seed + 40 + it).toByte() }
        val clientIv = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { (seed + it).toByte() }
        val serverIv = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { (seed + 20 + it).toByte() }
        val clientSend = JcaAesGcmAead(clientKey)
        val clientReceive = JcaAesGcmAead(serverKey)
        val serverSend = JcaAesGcmAead(serverKey)
        val serverReceive = JcaAesGcmAead(clientKey)
        val clock = TestClock()
        val initiator = SecureRecordLayer(
            SESSION_ID, SecurePeerRole.INITIATOR, clientSend, clientReceive, clientIv, serverIv, clock,
        )
        val responder = SecureRecordLayer(
            SESSION_ID, SecurePeerRole.RESPONDER, serverSend, serverReceive, serverIv, clientIv, clock,
        )
        clientKey.fill(0)
        serverKey.fill(0)
        clientIv.fill(0)
        serverIv.fill(0)
        return PairOfLayers(initiator, responder, clientSend, clientReceive, serverSend, serverReceive, clock)
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        val result = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            result[index * 2] = alphabet[value ushr 4]
            result[index * 2 + 1] = alphabet[value and 0x0F]
        }
        return String(result)
    }

    private class PairOfLayers(
        val initiator: SecureRecordLayer,
        val responder: SecureRecordLayer,
        val clientSend: JcaAesGcmAead,
        val clientReceive: JcaAesGcmAead,
        val serverSend: JcaAesGcmAead,
        val serverReceive: JcaAesGcmAead,
        val clock: TestClock,
    ) {
        fun close() {
            initiator.close()
            responder.close()
        }
    }

    private class TestClock(var now: Long = 10L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }

    /** Test adapter delegates to the platform AES-GCM implementation; it contains no test cipher. */
    private class JcaAesGcmAead(key: ByteArray) : SecureRecordAead {
        private val keyBytes = key.copyOf()
        @Volatile
        var closed: Boolean = false
            private set

        @Synchronized
        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
            check(!closed)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(plaintext)
        }

        @Synchronized
        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? {
            if (closed) return null
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(associatedData)
                cipher.doFinal(ciphertext)
            } catch (_: Exception) {
                null
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            keyBytes.fill(0)
        }
    }

    private companion object {
        val SESSION_ID = SessionId("00112233445566778899aabbccddeeff")
        val OTHER_SESSION_ID = SessionId("ffeeddccbbaa99887766554433221100")
        var nextSeed: Int = 1
    }
}
