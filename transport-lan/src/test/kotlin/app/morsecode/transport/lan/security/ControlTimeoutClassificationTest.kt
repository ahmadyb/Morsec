package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.ControlIoFailureContext
import app.morsecode.core.transfer.session.ControlIoOperation
import app.morsecode.core.transfer.session.ControlIoPhase
import app.morsecode.core.transfer.session.ControlIoRecordType
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecureControlSendResult
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime timeout classification through the production [SecureLanControlChannel].
 *
 * `CONTROL_TIMEOUT` on its own could not distinguish a PING receive deadline from a SESSION_CLOSE
 * one, because every timeout funnelled through `terminate(code)` and returned `SessionFailure(code)`
 * with nothing else attached. These tests drive the real channel over a real loopback socket pair
 * with a real [SecureRecordLayer], and assert both halves of the contract: the high-level code is
 * unchanged, and the structured context says which operation, which phase and which record type.
 *
 * WHAT IS DELIBERATELY NOT CLAIMED HERE. Send-side (`SEND`/`FLUSH`) deadlines need a peer that
 * stops reading and a socket whose send buffer actually fills. That is real kernel backpressure and
 * it lives in `BlockingWriteReleaseTest`, which blocks a genuine production write and proves the
 * deadline closes the owning socket and the write cannot report success afterwards. Loopback control
 * frames are tens of bytes against a kernel buffer of tens of kilobytes, so forcing that condition
 * here would mean shrinking buffers until the test measured the shim rather than the channel. The
 * send classification is pinned statically by verifier group 18 and exercised for its blocking
 * behaviour there, not faked here.
 *
 * Every wait is bounded and every peer is a daemon thread, so a regression fails the assertion
 * rather than hanging the suite.
 */
public class ControlTimeoutClassificationTest {

    private class JcaAesGcmAead(key: ByteArray) : SecureRecordAead {
        private val keyBytes = key.copyOf()

        @Synchronized
        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(plaintext)
        }

        @Synchronized
        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray? = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        } catch (_: Exception) {
            null
        }

        @Synchronized
        override fun close() {
            keyBytes.fill(0)
        }
    }

    /** Real advancing monotonic time: a fixed clock would leave `deadline - now` constant forever. */
    private class NanoClock : MonotonicClock {
        override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
    }

    /** One loopback pair with a production channel on the initiator side and a raw peer socket. */
    private class Fixture {
        val sessionId = SessionId("00112233445566778899aabbccddeeff")
        val clock = NanoClock()
        val listener = ServerSocket()
        val client = Socket()
        lateinit var peer: Socket
        var terminalRuns = 0
        lateinit var channel: SecureLanControlChannel

        /** A responder-side layer whose output the channel will accept as authentic. */
        lateinit var peerLayer: SecureRecordLayer

        fun open() {
            listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            client.connect(listener.localSocketAddress, 5_000)
            peer = listener.accept()
            channel = SecureLanControlChannel(
                socket = client,
                recordLayer = SecureRecordLayer(
                    sessionId = sessionId,
                    localRole = SecurePeerRole.INITIATOR,
                    sendAead = JcaAesGcmAead(ByteArray(32) { 1 }),
                    receiveAead = JcaAesGcmAead(ByteArray(32) { 2 }),
                    sendNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 3 },
                    receiveNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 4 },
                    clock = clock,
                ),
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = Long.MAX_VALUE,
                onTerminal = { terminalRuns++ },
            )
            peerLayer = SecureRecordLayer(
                sessionId = sessionId,
                localRole = SecurePeerRole.RESPONDER,
                // Mirrors the channel's receive side, so frames it produces verify successfully.
                sendAead = JcaAesGcmAead(ByteArray(32) { 2 }),
                receiveAead = JcaAesGcmAead(ByteArray(32) { 1 }),
                sendNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 4 },
                receiveNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 3 },
                clock = clock,
            )
        }

        fun close() {
            runCatching { channel.close() }
            runCatching { peerLayer.close() }
            runCatching { client.close() }
            runCatching { peer.close() }
            runCatching { listener.close() }
        }
    }

    /**
     * Writes a structurally valid header for [type] and then stalls, so the channel decodes the
     * header, learns the type, and times out during the body.
     */
    private fun stallAfterHeader(fixture: Fixture, type: SecureRecordType) {
        val header = SecureRecordCodec.encodeHeader(
            sessionId = fixture.sessionId,
            direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
            sequenceNumber = 0L,
            type = type,
            plaintextLength = 0,
        )
        val out = fixture.peer.getOutputStream()
        out.write(header)
        out.flush()
        // Drip the outstanding AEAD tag slowly enough that it cannot arrive inside the deadline,
        // while each individual byte still arrives well inside the remaining read window. That is
        // the shape that defeats a per-read inactivity timeout and requires an absolute one.
        Thread {
            try {
                repeat(SecureSessionLimits.AEAD_TAG_BYTES) {
                    out.write(byteArrayOf(0x42))
                    out.flush()
                    Thread.sleep(400L)
                }
            } catch (_: Exception) {
                // The deadline closes the socket; that is the point.
            }
        }.apply { isDaemon = true }.start()
    }

    private fun assertRefusedTimeout(
        result: SecureControlReceiveResult,
        expected: ControlIoFailureContext,
    ): SessionFailure {
        assertTrue("expected a refusal, got $result", result is SecureControlReceiveResult.Refused)
        val failure = (result as SecureControlReceiveResult.Refused).failure
        assertEquals(
            "the high-level code must remain CONTROL_TIMEOUT",
            SessionFailureCode.CONTROL_TIMEOUT,
            failure.code,
        )
        assertEquals("the structured context must identify operation, phase and type",
            expected, failure.controlIoContext)
        return failure
    }

    @Test
    public fun `a PING body timeout is classified as RECEIVE BODY PING`() {
        val fixture = Fixture()
        try {
            fixture.open()
            stallAfterHeader(fixture, SecureRecordType.PING)
            assertRefusedTimeout(
                fixture.channel.receive(),
                ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.PING),
            )
            assertTrue("the owning socket must be closed", fixture.client.isClosed)
            assertEquals("terminal teardown must have run exactly once", 1, fixture.terminalRuns)
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a PONG body timeout is classified as RECEIVE BODY PONG`() {
        val fixture = Fixture()
        try {
            fixture.open()
            stallAfterHeader(fixture, SecureRecordType.PONG)
            assertRefusedTimeout(
                fixture.channel.receive(),
                ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.PONG),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a SESSION_CLOSE body timeout is classified as RECEIVE BODY SESSION_CLOSE`() {
        val fixture = Fixture()
        try {
            fixture.open()
            stallAfterHeader(fixture, SecureRecordType.SESSION_CLOSE)
            assertRefusedTimeout(
                fixture.channel.receive(),
                ControlIoFailureContext(
                    ControlIoOperation.RECEIVE,
                    ControlIoPhase.BODY,
                    ControlIoRecordType.SESSION_CLOSE,
                ),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `the three receive-body classifications are distinguishable from each other`() {
        // The whole reason the context exists: these three were previously identical.
        val seen = mutableSetOf<ControlIoFailureContext>()
        for (type in listOf(SecureRecordType.PING, SecureRecordType.PONG, SecureRecordType.SESSION_CLOSE)) {
            val fixture = Fixture()
            try {
                fixture.open()
                stallAfterHeader(fixture, type)
                val result = fixture.channel.receive()
                assertTrue(result is SecureControlReceiveResult.Refused)
                seen.add(requireNotNull((result as SecureControlReceiveResult.Refused).failure.controlIoContext))
            } finally {
                fixture.close()
            }
        }
        assertEquals("each control type must produce its own classification", 3, seen.size)
    }

    @Test
    public fun `a header that never completes reports UNKNOWN rather than inventing a type`() {
        val fixture = Fixture()
        try {
            fixture.open()
            // A partial header, then silence. Nothing about the record is known at this point, so
            // attributing the timeout to PING would name a record that may never have been sent.
            val header = SecureRecordCodec.encodeHeader(
                sessionId = fixture.sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.SESSION_CLOSE,
                plaintextLength = 0,
            )
            val out = fixture.peer.getOutputStream()
            out.write(header.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES - 1))
            out.flush()
            assertRefusedTimeout(
                fixture.channel.receive(),
                ControlIoFailureContext(
                    ControlIoOperation.RECEIVE,
                    ControlIoPhase.HEADER,
                    ControlIoRecordType.UNKNOWN,
                ),
            )
            assertTrue(fixture.client.isClosed)
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a tampered record stays an authentication failure and never a timeout`() {
        val fixture = Fixture()
        try {
            fixture.open()
            val encoded = fixture.peerLayer.protect(SecureRecordType.PING, ByteArray(0))
            assertTrue("the peer frame must seal", encoded is SecureRecordResult.Encoded)
            val frame = (encoded as SecureRecordResult.Encoded).frameBytes()
            // Corrupt the final byte, which is inside the AEAD tag.
            frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
            fixture.peer.getOutputStream().apply {
                write(frame)
                flush()
            }
            val result = fixture.channel.receive()
            assertTrue(result is SecureControlReceiveResult.Refused)
            val failure = (result as SecureControlReceiveResult.Refused).failure
            assertEquals(
                "a broken tag is an authentication failure, not a deadline",
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
                failure.code,
            )
            assertNotEqualsCode(SessionFailureCode.CONTROL_TIMEOUT, failure.code)
        } finally {
            fixture.close()
        }
    }

    private fun assertNotEqualsCode(unexpected: SessionFailureCode, actual: SessionFailureCode) {
        assertFalse("the failure must not be reported as $unexpected", unexpected == actual)
    }

    @Test
    public fun `a peer that hangs up stays a premature close and never a timeout`() {
        val fixture = Fixture()
        try {
            fixture.open()
            fixture.peer.close()
            val result = fixture.channel.receive()
            assertTrue(result is SecureControlReceiveResult.Refused)
            val failure = (result as SecureControlReceiveResult.Refused).failure
            assertEquals(
                "EOF is a premature close, not a deadline",
                SessionFailureCode.CONTROL_CONNECT_FAILED,
                failure.code,
            )
            assertNull("no timeout context is attached to a non-timeout failure", failure.controlIoContext)
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a locally closed channel stays a cancellation and never a timeout`() {
        val fixture = Fixture()
        try {
            fixture.open()
            fixture.channel.close()
            val receive = fixture.channel.receive()
            assertTrue(receive is SecureControlReceiveResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (receive as SecureControlReceiveResult.Refused).failure.code,
            )
            val send = fixture.channel.send(SecureRecordType.PING, ByteArray(0))
            assertTrue(send is SecureControlSendResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (send as SecureControlSendResult.Refused).failure.code,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a channel that timed out cannot be revived by a later operation`() {
        val fixture = Fixture()
        try {
            fixture.open()
            stallAfterHeader(fixture, SecureRecordType.PING)
            assertRefusedTimeout(
                fixture.channel.receive(),
                ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.PING),
            )
            // Terminal means terminal: no sequence advance, no successful send, no second teardown.
            val again = fixture.channel.receive()
            assertTrue(again is SecureControlReceiveResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (again as SecureControlReceiveResult.Refused).failure.code,
            )
            val send = fixture.channel.send(SecureRecordType.PONG, ByteArray(0))
            assertTrue(send is SecureControlSendResult.Refused)
            assertEquals("teardown must not run twice", 1, fixture.terminalRuns)
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `the failure string stays redacted even when it carries structured context`() {
        val fixture = Fixture()
        try {
            fixture.open()
            stallAfterHeader(fixture, SecureRecordType.PING)
            val result = fixture.channel.receive()
            val failure = (result as SecureControlReceiveResult.Refused).failure
            // This is the string that reaches logs. Adding a field must not turn it into a dump.
            assertEquals(
                "SessionFailure(code=${SessionFailureCode.CONTROL_TIMEOUT.id})",
                failure.toString(),
            )
            assertFalse(failure.toString().contains("PING"))
            assertFalse(failure.toString().contains(fixture.sessionId.value))
        } finally {
            fixture.close()
        }
    }

    @Test
    public fun `a clean close does not report a close deadline expiry`() {
        val fixture = Fixture()
        try {
            fixture.open()
            assertFalse("nothing has expired yet", fixture.channel.closeDeadlineExpired)
            fixture.channel.close()
            assertFalse(
                "an unobstructed loopback close completes well inside its budget",
                fixture.channel.closeDeadlineExpired,
            )
            assertTrue(fixture.client.isClosed)
        } finally {
            fixture.close()
        }
    }
}
