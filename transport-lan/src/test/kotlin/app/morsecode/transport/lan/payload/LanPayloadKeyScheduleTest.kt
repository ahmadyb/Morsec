package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.transport.lan.security.pairing.JcaAesGcmAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The dormant payload capability, on its own.
 *
 * The subject is a small type with a large job: it is the only thing that can turn exported keying
 * material into a payload record layer, and it must do that exactly once. These tests are about the
 * one-shot property, the binding to a session and a role, and what closing means -- not about
 * encryption, which the record layer's own suite covers.
 *
 * The AEADs are the JDK's AES-GCM here. The device suite exercises the same composition against the
 * Conscrypt provider the shipped build uses.
 */
class LanPayloadKeyScheduleTest {

    @Test fun `an initiator sends on the initiator-to-responder direction`() {
        val schedule = schedule(role = SecurePeerRole.INITIATOR)
        val layer = requireNotNull(schedule.claimRecordLayer())
        val record = protected(layer, ByteArray(4) { 7 })
        assertEquals(SecureRecordDirection.INITIATOR_TO_RESPONDER, directionOf(record))
        layer.close()
    }

    @Test fun `a responder sends on the responder-to-initiator direction`() {
        val schedule = schedule(role = SecurePeerRole.RESPONDER)
        val layer = requireNotNull(schedule.claimRecordLayer())
        val record = protected(layer, ByteArray(4) { 7 })
        assertEquals(SecureRecordDirection.RESPONDER_TO_INITIATOR, directionOf(record))
        layer.close()
    }

    @Test fun `two peers of one handshake derive complementary layers`() {
        // Both sides receive the same four exporter outputs; only the role differs. That is what a
        // real handshake gives two peers, and it is the property that makes one layer's send the
        // other's receive.
        val initiator = schedule(role = SecurePeerRole.INITIATOR)
        val responder = schedule(role = SecurePeerRole.RESPONDER)
        val sending = requireNotNull(initiator.claimRecordLayer())
        val receiving = requireNotNull(responder.claimRecordLayer())
        val body = ByteArray(32) { (it * 3).toByte() }
        assertArrayEquals(body, opened(receiving, protected(sending, body)))
        // And the other way round, on the peers' other pair of keys.
        assertArrayEquals(body, opened(sending, protected(receiving, body)))
        sending.close()
        receiving.close()
    }

    @Test fun `a capability produces one layer and no more`() {
        val schedule = schedule()
        assertFalse(schedule.isClaimed())
        val first = requireNotNull(schedule.claimRecordLayer())
        assertTrue(schedule.isClaimed())
        assertNull("a second claim must return nothing", schedule.claimRecordLayer())
        first.close()
        schedule.close()
        assertNull("a closed capability produces nothing", schedule.claimRecordLayer())
    }

    @Test fun `closing before a claim destroys the material`() {
        val schedule = schedule()
        schedule.close()
        assertTrue(schedule.isClosed())
        assertNull(schedule.claimRecordLayer())
        // Closing again is harmless: the authority and the claim path can both reach this.
        schedule.close()
    }

    @Test fun `closing after a claim closes the layer it issued`() {
        val schedule = schedule()
        val layer = requireNotNull(schedule.claimRecordLayer())
        assertTrue(layer.isOpen())
        schedule.close()
        assertFalse(layer.isOpen())
        val refused = layer.protect(ByteArray(1))
        assertTrue("a closed layer must refuse: $refused", refused is LanPayloadProtectResult.Refused)
        assertEquals(
            LanPayloadLayerFailure.CLOSED,
            (refused as LanPayloadProtectResult.Refused).refusal.failure,
        )
    }

    @Test fun `the dead line is the control session's dead line`() {
        val schedule = schedule(pairStartedElapsedMillis = 1_000L)
        assertEquals(
            1_000L + SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS,
            schedule.expiryElapsedMillis,
        )
        assertEquals(1_000L, schedule.pairStartedElapsedMillis)
    }

    @Test fun `a pairing start near the end of time does not wrap`() {
        val schedule = schedule(pairStartedElapsedMillis = Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE, schedule.expiryElapsedMillis)
        schedule.close()
    }

    @Test fun `the transcript binding is an equality, not a prefix`() {
        val digest = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 1).toByte() }
        val schedule = schedule(transcriptDigest = digest)
        assertTrue(schedule.bindsTranscript(digest))
        assertFalse(schedule.bindsTranscript(ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES)))
        val prefix = digest.copyOf().also { it[0] = 0.toByte() }
        assertFalse(schedule.bindsTranscript(prefix))
        schedule.close()
    }

    @Test fun `the schedule refuses material it cannot use`() {
        assertIllegalArgument { schedule(transcriptDigest = ByteArray(31)) }
        assertIllegalArgument { schedule(c2sIv = ByteArray(11)) }
        assertIllegalArgument { schedule(s2cIv = ByteArray(13)) }
        assertIllegalArgument { schedule(pairStartedElapsedMillis = -1L) }
    }

    @Test fun `the schedule prints nothing that could be a key`() {
        val schedule = schedule()
        val text = schedule.toString()
        assertTrue("the schedule must be redacted: $text", text.contains("[redacted]"))
        assertFalse(text.contains(SESSION_HEX))
        assertFalse("a keyed AEAD must not leak its key: $text", text.contains("000102"))
        val layer = requireNotNull(schedule.claimRecordLayer())
        assertTrue(layer.toString().contains("[redacted]"))
        assertFalse(layer.toString().contains(SESSION_HEX))
        layer.close()
        schedule.close()
    }

    @Test fun `the schedule has no accessor that returns key material`() {
        // A structural assertion, and the point of the type: every declared member is either
        // metadata, a boolean, a record layer, or a close. If a `keys()` or a `keyBytes()` appears
        // later, this list stops matching.
        val declared = LanPayloadKeySchedule::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("access$") }
            .toSet()
        assertFalse(declared.any { it.contains("key", ignoreCase = true) && it != "isClaimed" })
        assertFalse(declared.any { it.contains("iv", ignoreCase = true) || it.contains("material") })
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun schedule(
        role: SecurePeerRole = SecurePeerRole.INITIATOR,
        sessionId: SessionId = SESSION_ID,
        transcriptDigest: ByteArray = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 1).toByte() },
        c2sIv: ByteArray = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 0x40 },
        s2cIv: ByteArray = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 0x50 },
        pairStartedElapsedMillis: Long = 0L,
    ): LanPayloadKeySchedule = LanPayloadKeySchedule.create(
        sessionId = sessionId,
        localRole = role,
        transcriptDigest = transcriptDigest,
        initiatorToResponderAead = JcaAesGcmAead(KEY_C2S.copyOf()),
        initiatorToResponderIv = c2sIv.copyOf(),
        responderToInitiatorAead = JcaAesGcmAead(KEY_S2C.copyOf()),
        responderToInitiatorIv = s2cIv.copyOf(),
        pairStartedElapsedMillis = pairStartedElapsedMillis,
        clock = MonotonicClock { 0L },
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

    private fun directionOf(record: ByteArray): SecureRecordDirection =
        requireNotNull(SecureRecordDirection.fromWireId(record[LanPayloadRecordFormat.OFF_DIRECTION].toInt() and 0xFF))

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected an IllegalArgumentException")
    }

    private companion object {
        val SESSION_HEX: String = "0123456789abcdef0123456789abcdef"
        val SESSION_ID: SessionId = SessionId(SESSION_HEX)
        val KEY_C2S: ByteArray = ByteArray(16) { (0x00 + it).toByte() }
        val KEY_S2C: ByteArray = ByteArray(16) { (0x80 + it).toByte() }
    }
}
