/*
 * The multiplexer over a real Conscrypt TLS 1.3 connection, on API 23.
 *
 * Everything that authenticates here is the shipped path: two production `SecureLanPairingCoordinator`
 * runs against each other, the real MODP J-PAKE exchange, the real exporters, the real authority and
 * the real record layers. The only test-only step is the one this slice is about -- claiming the
 * payload capability and handing both layers to the inert multiplexer instead of installing the
 * control-only channel. That composition exists nowhere in production.
 *
 * WHAT THIS ADDS OVER THE JVM SUITE
 *   The JVM suite substitutes the JDK's AES-GCM for Conscrypt and a loopback socket pair for TLS.
 *   Here the AEAD, the TLS record layer, the exporter output and the socket are the device's own.
 *   The fragmentation and priority cases therefore exercise a real SSLSocket's read and write paths.
 *
 * STATUS
 *   The multiplexer remains inert: no provider, session, service, worker, storage adapter or UI
 *   constructs it, and `PayloadTransferGate` still refuses every session. Experimental,
 *   project-defined, not independently cryptographically reviewed; see
 *   doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.TransferFrame
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.payload.LanPayloadProtectResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class LanMultiplexedRecordChannelApi23Test {

    // --- the live path ----------------------------------------------------------------------------

    @Test(timeout = 240_000L)
    public fun controlAndPayloadRecordsCrossOneConscryptSocketInOrder() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val initiatorMux = mux(initiator.secureSocket, SecurePeerRole.INITIATOR, initiatorClaim)
        val responderMux = mux(responder.secureSocket, SecurePeerRole.RESPONDER, responderClaim)
        try {
            // The gate is unmoved by a successful control authentication: this slice installs no
            // payload path in production, and the decision still says so on the device.
            assertTrue(
                "payload must stay refused after a control authentication",
                PayloadTransferGate.evaluate(initiatorClaim.controlSession)
                    is PayloadTransferDecision.Refused,
            )

            val sentFrame = frame(initiatorClaim, 32, sequence = 0L)
            assertEquals(
                LanMultiplexedSend.Sent,
                initiatorMux.sendControl(SecureRecordType.PING),
            )
            assertEquals(
                LanMultiplexedSend.Sent,
                initiatorMux.sendPayloadFrame(sentFrame),
            )

            val control = responderMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a control record, got $control", control is LanMultiplexedReceive.Control)
            assertEquals(SecureRecordType.PING, (control as LanMultiplexedReceive.Control).record.type)

            val payload = responderMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a payload record, got $payload", payload is LanMultiplexedReceive.Payload)
            val receivedFrame = (payload as LanMultiplexedReceive.Payload).frame
            assertTrue("the payload must arrive intact", receivedFrame.payloadIsIntact())
            assertTrue(
                "the payload must arrive unchanged",
                sentFrame.payload.contentEquals(receivedFrame.payload),
            )

            assertEquals(
                LanMultiplexedSend.Sent,
                responderMux.sendControl(SecureRecordType.PONG),
            )
            val pong = initiatorMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a control record, got $pong", pong is LanMultiplexedReceive.Control)
            assertEquals(SecureRecordType.PONG, (pong as LanMultiplexedReceive.Control).record.type)
        } finally {
            initiatorMux.close()
            responderMux.close()
            pairing.close()
        }
    }

    @Test(timeout = 240_000L)
    public fun alternatingRecordsArriveInOrder() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val initiatorMux = mux(initiator.secureSocket, SecurePeerRole.INITIATOR, initiatorClaim)
        val responderMux = mux(responder.secureSocket, SecurePeerRole.RESPONDER, responderClaim)
        try {
            repeat(3) { index ->
                assertEquals(LanMultiplexedSend.Sent, initiatorMux.sendControl(SecureRecordType.PING))
                assertEquals(
                    LanMultiplexedSend.Sent,
                    initiatorMux.sendPayloadFrame(frame(initiatorClaim, 8, sequence = index.toLong())),
                )
            }
            repeat(3) { index ->
                val control = responderMux.receiveNext(api23Clock().nowMillis() + 8_000L)
                assertTrue("expected a control record, got $control", control is LanMultiplexedReceive.Control)
                val payload = responderMux.receiveNext(api23Clock().nowMillis() + 8_000L)
                assertTrue("expected a payload record, got $payload", payload is LanMultiplexedReceive.Payload)
                assertEquals(index.toLong(), (payload as LanMultiplexedReceive.Payload).frame.sequence)
            }
        } finally {
            initiatorMux.close()
            responderMux.close()
            pairing.close()
        }
    }

    /**
     * A payload record delivered a few bytes at a time by a real TLS socket.
     *
     * The throttled stream is on the reading side only: Conscrypt still decodes its own TLS records,
     * but every `read` returns at most eleven bytes, so the multiplexer's four-byte prefix and its
     * header and body loops are exercised across polling slices against a live socket.
     */
    @Test(timeout = 240_000L)
    public fun aFragmentedPayloadRecordIsReassembledAcrossSlices() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val senderMux = mux(responder.secureSocket, SecurePeerRole.RESPONDER, responderClaim)
        val trickled = TrickleSocket(initiator.secureSocket, maxChunk = 11, pauseMillis = 2L)
        val readerMux = mux(trickled, SecurePeerRole.INITIATOR, initiatorClaim)
        try {
            val chunk = ByteArray(4_096) { (it * 13).toByte() }
            val sent = frameWithPayload(responderClaim, chunk, sequence = 0L)
            assertEquals(LanMultiplexedSend.Sent, senderMux.sendPayloadFrame(sent))

            val received = readerMux.receiveNext(api23Clock().nowMillis() + 15_000L)
            assertTrue("expected a payload record, got $received", received is LanMultiplexedReceive.Payload)
            val frame = (received as LanMultiplexedReceive.Payload).frame
            assertTrue("the payload must arrive intact", frame.payloadIsIntact())
            assertTrue(chunk.contentEquals(frame.payload))
        } finally {
            readerMux.close()
            senderMux.close()
            pairing.close()
        }
    }

    /**
     * A control record cannot interrupt the payload record already being written, and it wins the
     * next slot.
     *
     * The gate sits on the responder socket's output: the payload write blocks inside Conscrypt while
     * it holds the writer, a control send queues behind it, and when the gate opens the control record
     * is the next one on the stream.
     */
    @Test(timeout = 240_000L)
    public fun aControlRecordWaitsForTheRecordInProgressThenWinsTheNextSlot() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val gate = GatedSocket(responder.secureSocket)
        val senderMux = mux(gate, SecurePeerRole.RESPONDER, responderClaim)
        val readerMux = mux(initiator.secureSocket, SecurePeerRole.INITIATOR, initiatorClaim)
        try {
            val payloadThread = Thread {
                senderMux.sendPayloadFrame(frame(responderClaim, 16, sequence = 0L))
            }
            payloadThread.start()
            assertTrue("the payload write must reach the socket", gate.awaitEntered())

            val controlThread = Thread { senderMux.sendControl(SecureRecordType.PONG) }
            controlThread.start()
            Thread.sleep(200L)
            assertTrue("the control record must still be waiting", controlThread.isAlive)

            gate.release()
            payloadThread.join(10_000L)
            controlThread.join(10_000L)
            assertFalse(payloadThread.isAlive)
            assertFalse(controlThread.isAlive)

            val payload = readerMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a payload record, got $payload", payload is LanMultiplexedReceive.Payload)
            val control = readerMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a control record, got $control", control is LanMultiplexedReceive.Control)
            assertEquals(SecureRecordType.PONG, (control as LanMultiplexedReceive.Control).record.type)
        } finally {
            readerMux.close()
            senderMux.close()
            pairing.close()
        }
    }

    // --- refusals with real Conscrypt keys --------------------------------------------------------

    /**
     * A record the initiator itself produced is offered to the initiator's own payload layer.
     *
     * The AEADs are the ones the exporter derived for this session; only the transport is a byte
     * buffer. The direction field is checked before the tag, so the refusal is a direction mismatch.
     */
    @Test(timeout = 240_000L)
    public fun aReflectedPayloadRecordIsRefusedByDirection() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val layer = requireNotNull(initiatorClaim.payload?.claimRecordLayer()) {
            "the authenticated chain must carry payload material"
        }
        try {
            val reflected = layer.protect(FrameCodec.encode(frame(initiatorClaim, 8, sequence = 0L)))
            assertTrue(
                "the layer must produce a record to reflect: $reflected",
                reflected is LanPayloadProtectResult.Protected,
            )
            val reader = LanMultiplexedRecordChannel(
                socket = ByteFeedSocket(
                    (reflected as LanPayloadProtectResult.Protected)
                        .wireRecord,
                ),
                controlLayer = initiatorClaim.recordLayer,
                payloadLayer = layer,
                role = SecurePeerRole.INITIATOR,
                clock = api23Clock(),
                expiresAtElapsedMillis = initiatorClaim.expiresAtElapsedMillis,
            )
            val outcome = reader.receiveNext(api23Clock().nowMillis() + 5_000L)
            assertTrue("expected a refusal, got $outcome", outcome is LanMultiplexedReceive.Refused)
            assertEquals(
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
                (outcome as LanMultiplexedReceive.Refused).failure.code,
            )
            assertFalse(reader.isOpen())
            reader.close()
        } finally {
            pairing.close()
        }
    }

    @Test(timeout = 240_000L)
    public fun aPayloadTagFailureIsRefusedWithRealConscryptAeads() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val responderLayer = requireNotNull(responderClaim.payload?.claimRecordLayer())
        val initiatorLayer = requireNotNull(initiatorClaim.payload?.claimRecordLayer())
        try {
            val wire = (responderLayer.protect(FrameCodec.encode(frame(responderClaim, 8, sequence = 0L)))
                as LanPayloadProtectResult.Protected).wireRecord
            wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x01).toByte()

            val reader = LanMultiplexedRecordChannel(
                socket = ByteFeedSocket(wire),
                controlLayer = initiatorClaim.recordLayer,
                payloadLayer = initiatorLayer,
                role = SecurePeerRole.INITIATOR,
                clock = api23Clock(),
                expiresAtElapsedMillis = initiatorClaim.expiresAtElapsedMillis,
            )
            val outcome = reader.receiveNext(api23Clock().nowMillis() + 5_000L)
            assertTrue("expected a refusal, got $outcome", outcome is LanMultiplexedReceive.Refused)
            assertEquals(
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
                (outcome as LanMultiplexedReceive.Refused).failure.code,
            )
            assertFalse(reader.isOpen())
            reader.close()
        } finally {
            pairing.close()
        }
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @Test(timeout = 240_000L)
    public fun closeClosesTheLiveSocketOnceAndLeavesNoChannelOpen() {
        val pairing = pair()
        val initiator = authenticated(pairing.initiatorResult)
        val responder = authenticated(pairing.responderResult)
        val initiatorClaim = requireNotNull(initiator.authority.claim())
        val responderClaim = requireNotNull(responder.authority.claim())
        val initiatorMux = mux(initiator.secureSocket, SecurePeerRole.INITIATOR, initiatorClaim)
        val responderMux = mux(responder.secureSocket, SecurePeerRole.RESPONDER, responderClaim)
        try {
            assertEquals(LanMultiplexedSend.Sent, initiatorMux.sendControl(SecureRecordType.PING))
            val received = responderMux.receiveNext(api23Clock().nowMillis() + 8_000L)
            assertTrue("expected a control record, got $received", received is LanMultiplexedReceive.Control)

            initiatorMux.close()
            initiatorMux.close()
            assertFalse(initiatorMux.isOpen())
            assertTrue("the multiplexer must close the socket it owns", initiator.secureSocket.isClosed)
            assertTrue(
                "no write is accepted after close",
                initiatorMux.sendControl(SecureRecordType.PONG) is LanMultiplexedSend.Refused,
            )
            val afterClose = initiatorMux.receiveNext(api23Clock().nowMillis() + 1_000L)
            assertTrue("expected a refusal, got $afterClose", afterClose is LanMultiplexedReceive.Refused)
        } finally {
            responderMux.close()
            pairing.close()
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun pair(): Api23Pairing {
        val board = Api23CodeBoard()
        return api23RunPairing(
            initiatorHuman = Api23Human(
                board = board,
                initiator = true,
                enteredCodes = emptyList(),
                approval = SecureApprovalDecision.APPROVE,
            ),
            responderHuman = Api23Human(
                board = board,
                initiator = false,
                enteredCodes = listOf({ board.peek()?.toCharArray() }),
                approval = SecureApprovalDecision.APPROVE,
            ),
            board = board,
        )
    }

    private fun authenticated(outcome: SecurePairingRunResult): SecurePairingRunResult.Authenticated =
        outcome as? SecurePairingRunResult.Authenticated
            ?: throw AssertionError("pairing did not authenticate: $outcome")

    private fun mux(
        socket: Socket,
        role: SecurePeerRole,
        claim: AuthenticatedControlAuthority.AuthenticatedControlChannel,
    ): LanMultiplexedRecordChannel {
        val payload = requireNotNull(claim.payload?.claimRecordLayer()) {
            "the authenticated chain must carry payload material"
        }
        return LanMultiplexedRecordChannel(
            socket = socket,
            controlLayer = claim.recordLayer,
            payloadLayer = payload,
            role = role,
            clock = api23Clock(),
            expiresAtElapsedMillis = claim.expiresAtElapsedMillis,
        )
    }

    private fun frame(
        claim: AuthenticatedControlAuthority.AuthenticatedControlChannel,
        payloadSize: Int,
        sequence: Long,
    ): TransferFrame = frameWithPayload(
        claim = claim,
        payload = ByteArray(payloadSize) { (it * 7).toByte() },
        sequence = sequence,
    )

    private fun frameWithPayload(
        claim: AuthenticatedControlAuthority.AuthenticatedControlChannel,
        payload: ByteArray,
        sequence: Long,
    ): TransferFrame = FrameCodec.dataChunk(
        sessionId = claim.recordLayer.sessionId,
        transferId = TransferId("transfer-1"),
        offset = 0L,
        sequence = sequence,
        bytes = payload,
    )

    // --- test doubles -----------------------------------------------------------------------------

    /** Returns at most [maxChunk] bytes per read, so the reader meets the socket in slices. */
    private class TrickleSocket(
        private val delegate: Socket,
        maxChunk: Int,
        pauseMillis: Long,
    ) : Socket() {
        private val throttled = TrickleInputStream(delegate.getInputStream(), maxChunk, pauseMillis)
        private var timeout: Int = 0

        override fun getInputStream(): InputStream = throttled

        override fun getOutputStream(): OutputStream = delegate.getOutputStream()

        override fun getSoTimeout(): Int = delegate.soTimeout

        override fun setSoTimeout(value: Int) {
            timeout = value
            delegate.soTimeout = value
        }

        override fun close() = delegate.close()

        override fun isClosed(): Boolean = delegate.isClosed
    }

    private class TrickleInputStream(
        private val delegate: InputStream,
        private val maxChunk: Int,
        private val pauseMillis: Long,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            Thread.sleep(pauseMillis)
            return delegate.read(bytes, offset, minOf(length, maxChunk))
        }

        override fun close() = delegate.close()
    }

    /** Blocks the first record write inside the real TLS output, then lets it through. */
    private class GatedSocket(private val delegate: Socket) : Socket() {
        private val gated = GatedOutputStream(delegate.getOutputStream())

        fun awaitEntered(): Boolean = gated.awaitEntered()

        fun release() = gated.release()

        override fun getInputStream(): InputStream = delegate.getInputStream()

        override fun getOutputStream(): OutputStream = gated

        override fun getSoTimeout(): Int = delegate.soTimeout

        override fun setSoTimeout(value: Int) {
            delegate.soTimeout = value
        }

        override fun close() = delegate.close()

        override fun isClosed(): Boolean = delegate.isClosed
    }

    private class GatedOutputStream(private val delegate: OutputStream) : OutputStream() {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitEntered(): Boolean = entered.await(10, TimeUnit.SECONDS)

        fun release() = released.countDown()

        override fun write(value: Int) = delegate.write(value)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            released.await(15, TimeUnit.SECONDS)
            delegate.write(bytes, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    /** A socket over a fixed byte buffer; used only where a real AEAD reads an in-memory record. */
    private class ByteFeedSocket(private val wire: ByteArray) : Socket() {
        private var timeout: Int = 0

        override fun getInputStream(): InputStream = ByteArrayInputStream(wire)

        override fun getOutputStream(): OutputStream = DiscardingOutputStream

        override fun getSoTimeout(): Int = timeout

        override fun setSoTimeout(value: Int) {
            timeout = value
        }

        override fun close() = Unit
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

        override fun flush() = Unit
    }
}
