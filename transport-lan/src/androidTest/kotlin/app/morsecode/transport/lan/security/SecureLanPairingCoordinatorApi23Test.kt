/*
 * The live pairing path on API 23: the switch, exercised where the exporter is real.
 *
 * Every case here calls `SecureLanPairingCoordinator.run()` on both endpoints over a real Conscrypt
 * TLS 1.3 loopback connection. There is no SAS anywhere: the initiator displays an entered code, the
 * responder types it back through a scripted human, and both approve the intended peer after the
 * PAKE confirms. The authority is claimed exactly once and the payload gate still refuses.
 *
 * The refusals are as important as the success. A wrong code costs an attempt, three wrong codes
 * exhaust the code, a rejection by either side ends the pairing without authority, and silence
 * expires rather than approving. Each case names what it expects; none of them asserts "something
 * failed".
 */

package app.morsecode.transport.lan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.transfer.session.ControlIoFailureContext
import app.morsecode.core.transfer.session.ControlIoOperation
import app.morsecode.core.transfer.session.ControlIoPhase
import app.morsecode.core.transfer.session.ControlIoRecordType
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecureControlSendResult
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class SecureLanPairingCoordinatorApi23Test {

    @Test(timeout = 180_000L)
    public fun realEnteredCodePairingProducesAClaimableControlOnlyAuthority() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = APPROVE,
            ),
            board = board,
        )
        var clientChannel: SecureLanControlChannel? = null
        var serverChannel: SecureLanControlChannel? = null
        try {
            val client = pairing.initiatorResult as? SecurePairingRunResult.Authenticated
                ?: throw AssertionError("the initiator did not authenticate: ${pairing.initiatorResult}")
            val peer = pairing.responderResult as? SecurePairingRunResult.Authenticated
                ?: throw AssertionError("the responder did not authenticate: ${pairing.responderResult}")
            assertEquals("the initiator displayed exactly one code", 1, board.shownCount)
            assertEquals(
                "the initiator asked the intended-peer question exactly once",
                1,
                pairing.initiatorHuman.approvalPrompts,
            )

            // Single use, and control-only. The negotiated session the authority was issued for
            // still reports the unauthenticated control state, exactly as the public contract says.
            val clientClaim = client.authority.claim()
            assertNotNull("a completed pairing must hand out a control channel", clientClaim)
            assertNull("a control authority is single use", client.authority.claim())
            val peerClaim = requireNotNull(peer.authority.claim())
            assertTrue(client.authority.isClaimed())
            assertEquals(
                app.morsecode.core.transfer.session.SessionSecurityState.ControlOnlyUnauthenticated,
                requireNotNull(clientClaim).controlSession.security,
            )
            assertTrue(
                "payload must stay refused after a successful control authentication",
                PayloadTransferGate.evaluate(requireNotNull(clientClaim).controlSession)
                    is PayloadTransferDecision.Refused,
            )

            clientChannel = SecureLanControlChannel(
                socket = client.secureSocket,
                recordLayer = requireNotNull(clientClaim).recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = api23Clock(),
                expiresAtElapsedMillis = clientClaim.expiresAtElapsedMillis,
                onTerminal = {},
            )
            serverChannel = SecureLanControlChannel(
                socket = peer.secureSocket,
                recordLayer = peerClaim.recordLayer,
                role = SecurePeerRole.RESPONDER,
                clock = api23Clock(),
                expiresAtElapsedMillis = peerClaim.expiresAtElapsedMillis,
                onTerminal = {},
            )
            assertTrue(
                clientChannel.send(SecureRecordType.KEY_CONFIRMATION, byteArrayOf()) is
                    SecureControlSendResult.Refused,
            )
            assertEquals(SecureControlSendResult.Sent, clientChannel.send(SecureRecordType.PING, byteArrayOf()))
            val ping = serverChannel.receive() as SecureControlReceiveResult.Record
            assertEquals(SecureRecordType.PING, ping.value.type)
            ping.value.clearSensitive()
            assertEquals(SecureControlSendResult.Sent, serverChannel.send(SecureRecordType.PONG, byteArrayOf()))
            val pong = clientChannel.receive() as SecureControlReceiveResult.Record
            assertEquals(SecureRecordType.PONG, pong.value.type)
            pong.value.clearSensitive()
        } finally {
            clientChannel?.close()
            serverChannel?.close()
            pairing.close()
        }
    }

    @Test(timeout = 180_000L)
    public fun aWrongCodeIsRetriedAndTheSecondAttemptAuthenticates() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                // The first entry is a well-formed code that is not the displayed one; the second is
                // whatever the initiator displayed. The retry rule must allow exactly this.
                enteredCodes = listOf({ wrongCode() }, { carryAcross(board) }),
                approval = APPROVE,
            ),
            board = board,
        )
        try {
            assertTrue(
                "a wrong code followed by the right one must authenticate; ${pairing.describeOutcome()}",
                pairing.initiatorResult is SecurePairingRunResult.Authenticated,
            )
            assertTrue(pairing.responderResult is SecurePairingRunResult.Authenticated)
            assertEquals(
                "the responder typed a code for each attempt",
                2,
                pairing.responderHuman.enteredCodeRequests,
            )
        } finally {
            pairing.close()
        }
    }

    @Test(timeout = 240_000L)
    public fun threeWrongCodesExhaustTheCodeAndGrantNoAuthority() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ wrongCode() }),
                approval = APPROVE,
            ),
            board = board,
        )
        try {
            assertFalse(
                "an exhausted code still authenticated",
                pairing.initiatorResult is SecurePairingRunResult.Authenticated,
            )
            assertFalse(
                "an exhausted code still authenticated on the typing side",
                pairing.responderResult is SecurePairingRunResult.Authenticated,
            )
            assertEquals(
                "the budget is three attempts, and no more may be asked for",
                3,
                pairing.responderHuman.enteredCodeRequests,
            )
            assertEquals(
                "the code was never authenticated, so the intended-peer question must not be asked",
                0,
                pairing.initiatorHuman.approvalPrompts,
            )
        } finally {
            pairing.close()
        }
    }

    @Test(timeout = 180_000L)
    public fun aLocalRejectionEndsThePairingAndNoAuthorityIsIssued() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = SecureApprovalDecision.REJECT,
            ),
            board = board,
        )
        try {
            assertFalse(
                "a rejection still authenticated",
                pairing.responderResult is SecurePairingRunResult.Authenticated,
            )
            assertFalse(
                "the peer of a rejection still authenticated",
                pairing.initiatorResult is SecurePairingRunResult.Authenticated,
            )
            assertEquals(
                SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED,
                pairing.responderFailureCode(),
            )
            // The peer learns the same word -- "declined" -- and nothing about who decided, which
            // is what keeps a rejection from becoming an oracle for the remote user's screen.
            assertEquals(
                SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED,
                pairing.initiatorFailureCode(),
            )
            assertEquals(
                "both humans were asked, and neither result carries authority",
                1,
                pairing.initiatorHuman.approvalPrompts,
            )
        } finally {
            pairing.close()
        }
    }

    @Test(timeout = 240_000L)
    public fun aPeerRejectionCostsTheLocalAttemptBudgetNothing() {
        val clock = api23Clock()
        val scope = PairingAdmissionScope(monotonicMillis = clock::nowMillis)
        val board = Api23CodeBoard()
        val peerId = "00000000000000000000000000000002"
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = SecureApprovalDecision.REJECT,
            ),
            board = board,
            initiatorScope = scope,
        )
        try {
            assertFalse(pairing.initiatorResult is SecurePairingRunResult.Authenticated)
            assertEquals("the permit is released exactly once", 0, scope.concurrentPairings())
            assertFalse(
                "the remote human's decision must not condemn the remote identity",
                scope.isIdentityInvalidated(peerId),
            )
        } finally {
            pairing.close()
            scope.close()
        }
    }

    @Test(timeout = 180_000L)
    public fun anUnansweredApprovalExpiresInsteadOfApproving() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = null),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = APPROVE,
            ),
            board = board,
        )
        try {
            assertFalse(
                "silence was treated as approval",
                pairing.initiatorResult is SecurePairingRunResult.Authenticated,
            )
            assertEquals(
                "silence must expire rather than approve; ${pairing.describeOutcome()}",
                SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED,
                pairing.initiatorFailureCode(),
            )
        } finally {
            pairing.close()
        }
    }

    @Test(timeout = 120_000L)
    public fun cancellationBeforeTlsIsRefusedBeforeAnyCryptographicWork() {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(
                board,
                initiator = true,
                enteredCodes = emptyList(),
                approval = APPROVE,
                cancelSignal = { true },
            ),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = APPROVE,
            ),
            board = board,
        )
        try {
            assertEquals(SessionFailureCode.OPERATION_CANCELLED, pairing.initiatorFailureCode())
            assertFalse(pairing.responderResult is SecurePairingRunResult.Authenticated)
        } finally {
            pairing.close()
        }
    }

    /**
     * Deadline expiry on the real production channel over a real API 23 Conscrypt handshake.
     *
     * The first test above proves the honest path: pairing authenticates, PING/PONG round-trip, and
     * no timeout context is emitted. This one proves the other half -- that a peer which supplies
     * too little is ended by the operation's one absolute deadline, and that the failure says which
     * phase and which record type, which `CONTROL_TIMEOUT` alone could not.
     *
     * EVIDENCE SPLIT, STATED PLAINLY. This covers the *receive* side on real API 23 sockets, because
     * a peer that withholds bytes is trivially deterministic. It does not cover the *write* side:
     * control frames are tens of bytes against an emulator socket buffer of tens of kilobytes, so
     * forcing a genuine blocked write here would mean shrinking buffers until the test measured the
     * shim rather than the channel, and calling that real-world backpressure would be false.
     * OS-level blocking-write evidence lives in the JVM `BlockingWriteReleaseTest`.
     */
    @Test(timeout = 180_000L)
    public fun receiveDeadlineOnRealApi23ChannelClassifiesHeaderAndBodyPhases() {
        var clientChannel: SecureLanControlChannel? = null
        var clientAuthority: AuthenticatedControlAuthority? = null
        var peerAuthority: AuthenticatedControlAuthority? = null
        var peerSocket: Socket? = null
        try {
            val pair = authenticatedPair()
            clientAuthority = pair.initiatorResult.authority
            peerAuthority = pair.responderResult.authority
            peerSocket = pair.responderResult.secureSocket
            val claim = requireNotNull(clientAuthority.claim())
            val stream = requireNotNull(peerAuthority.claim())
            var terminalRuns = 0
            val channel = SecureLanControlChannel(
                socket = pair.initiatorResult.secureSocket,
                recordLayer = claim.recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = api23Clock(),
                expiresAtElapsedMillis = claim.expiresAtElapsedMillis,
                onTerminal = { terminalRuns++ },
            )
            clientChannel = channel

            // --- header phase: a header that never completes, then silence -------------------
            val header = SecureRecordCodec.encodeHeader(
                sessionId = claim.recordLayer.sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.PING,
                plaintextLength = 0,
            )
            val peerOut = pair.responderResult.secureSocket.getOutputStream()
            peerOut.write(header.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES - 1))
            peerOut.flush()

            val headerTimeout = channel.receive()
            assertTrue(
                "an incomplete header must be refused, got $headerTimeout",
                headerTimeout is SecureControlReceiveResult.Refused,
            )
            val headerFailure = (headerTimeout as SecureControlReceiveResult.Refused).failure
            assertEquals(SessionFailureCode.CONTROL_TIMEOUT, headerFailure.code)
            assertEquals(
                "no type may be fabricated before the header decodes",
                ControlIoFailureContext(
                    operation = ControlIoOperation.RECEIVE,
                    phase = ControlIoPhase.HEADER,
                    recordType = ControlIoRecordType.UNKNOWN,
                ),
                headerFailure.controlIoContext,
            )
            assertTrue("the deadline must close the owning socket", pair.initiatorResult.secureSocket.isClosed)
            assertEquals("terminal teardown runs exactly once", 1, terminalRuns)

            val afterTimeout = channel.receive()
            assertTrue(afterTimeout is SecureControlReceiveResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (afterTimeout as SecureControlReceiveResult.Refused).failure.code,
            )
            assertEquals("teardown must not run twice", 1, terminalRuns)
            channel.close()
            channel.close()
            assertEquals(1, terminalRuns)
            // The claimed record layer is what the channel used; closing it twice must stay safe.
            stream.recordLayer.close()
        } finally {
            clientChannel?.close()
            clientAuthority?.close()
            peerAuthority?.close()
            runCatching { peerSocket?.close() }
        }
    }

    /**
     * Body-phase expiry on the real production channel: a complete, valid header arrives so the type
     * is known, then the AEAD tag stalls. The failure must name the record type, which is the whole
     * reason the structured context exists.
     */
    @Test(timeout = 180_000L)
    public fun receiveBodyDeadlineOnRealApi23ChannelNamesTheDecodedRecordType() {
        var clientChannel: SecureLanControlChannel? = null
        var clientAuthority: AuthenticatedControlAuthority? = null
        var peerAuthority: AuthenticatedControlAuthority? = null
        try {
            val pair = authenticatedPair()
            clientAuthority = pair.initiatorResult.authority
            peerAuthority = pair.responderResult.authority
            val claim = requireNotNull(clientAuthority.claim())
            requireNotNull(peerAuthority.claim())
            val channel = SecureLanControlChannel(
                socket = pair.initiatorResult.secureSocket,
                recordLayer = claim.recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = api23Clock(),
                expiresAtElapsedMillis = claim.expiresAtElapsedMillis,
                onTerminal = {},
            )
            clientChannel = channel

            val header = SecureRecordCodec.encodeHeader(
                sessionId = claim.recordLayer.sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.PING,
                plaintextLength = 0,
            )
            val peerOut = pair.responderResult.secureSocket.getOutputStream()
            peerOut.write(header)
            peerOut.flush()
            // PING carries no payload, so the whole outstanding body is the AEAD tag. Drip it slowly
            // enough that it cannot arrive inside the absolute deadline, while each byte still
            // arrives well inside the per-read window -- the shape that defeats an inactivity timer.
            Thread {
                try {
                    repeat(SecureSessionLimits.AEAD_TAG_BYTES) {
                        peerOut.write(byteArrayOf(0x42))
                        peerOut.flush()
                        Thread.sleep(400L)
                    }
                } catch (_: Exception) {
                    // The deadline closes the socket; that is the point.
                }
            }.apply { isDaemon = true }.start()

            val bodyTimeout = channel.receive()
            assertTrue(
                "an incomplete body must be refused, got $bodyTimeout",
                bodyTimeout is SecureControlReceiveResult.Refused,
            )
            val bodyFailure = (bodyTimeout as SecureControlReceiveResult.Refused).failure
            assertEquals(SessionFailureCode.CONTROL_TIMEOUT, bodyFailure.code)
            assertEquals(
                "the header decoded, so the type must be reported rather than left UNKNOWN",
                ControlIoFailureContext(
                    operation = ControlIoOperation.RECEIVE,
                    phase = ControlIoPhase.BODY,
                    recordType = ControlIoRecordType.PING,
                ),
                bodyFailure.controlIoContext,
            )
            assertTrue("the deadline must close the owning socket", pair.initiatorResult.secureSocket.isClosed)
            val after = channel.receive()
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (after as SecureControlReceiveResult.Refused).failure.code,
            )
        } finally {
            clientChannel?.close()
            clientAuthority?.close()
            peerAuthority?.close()
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A code the initiator never displayed. Typing it must cost exactly one attempt. */
    private fun wrongCode(): CharArray = "23456789".toCharArray()

    /** The code the initiator displayed, fetched at answer time. */
    private fun carryAcross(board: Api23CodeBoard): CharArray? =
        board.await(6_000L)?.toCharArray()

    private class AuthenticatedPair(
        val initiatorResult: SecurePairingRunResult.Authenticated,
        val responderResult: SecurePairingRunResult.Authenticated,
    )

    private fun authenticatedPair(): AuthenticatedPair {
        val board = Api23CodeBoard()
        val pairing = api23RunPairing(
            initiatorHuman = Api23Human(board, initiator = true, enteredCodes = emptyList(), approval = APPROVE),
            responderHuman = Api23Human(
                board,
                initiator = false,
                enteredCodes = listOf({ carryAcross(board) }),
                approval = APPROVE,
            ),
            board = board,
        )
        val initiator = pairing.initiatorResult as? SecurePairingRunResult.Authenticated
            ?: throw AssertionError("the initiator did not authenticate; ${pairing.describeOutcome()}")
        val responder = pairing.responderResult as? SecurePairingRunResult.Authenticated
            ?: throw AssertionError("the responder did not authenticate; ${pairing.describeOutcome()}")
        // The pair's executor is done; the sockets and authorities are handed to the caller.
        pairing.executor.shutdown()
        return AuthenticatedPair(initiator, responder)
    }

    private companion object {
        val APPROVE: SecureApprovalDecision = SecureApprovalDecision.APPROVE
    }
}
