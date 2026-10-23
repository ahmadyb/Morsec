/*
 * The composed chain, end to end on the JVM: selection, PAKE, approvals, record layer, both key
 * confirmations, authority.
 *
 * The transports are in-memory and the TLS exporter is deterministic (see the harness header);
 * everything else -- the PAKE arithmetic, the serialized control records, AES-GCM records, the
 * reducer and the authority -- is the production code. Real Conscrypt TLS is exercised by
 * `PakeSessionApi23Test` on a device.
 *
 * These tests do not and cannot show the construction is secure. See
 * doc/security/owner-risk-waiver-2026-10-19.md. Nothing here is authority for payload.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PakeSessionCoordinatorTest {

    // --- the honest path ------------------------------------------------------------------

    @Test
    fun anHonestRunReachesAuthorityOnBothSides() {
        val fixture = PakeSessionFixture()
        val initiator = fixture.initiator(fixture.initiatorInteraction())
        val responder = fixture.responder(fixture.responderInteraction())

        val (left, right) = runCoordinators(initiator, responder)

        val fromInitiator = authenticated(left)
        val fromResponder = authenticated(right)
        assertEquals(PakePairingState.AUTHENTICATED, initiator.state())
        assertEquals(PakePairingState.AUTHENTICATED, responder.state())
        assertEquals(1, fromInitiator.attemptOrdinal)
        assertEquals(1, fromResponder.attemptOrdinal)
        assertNotNull(fromInitiator.authority)
        assertNotNull(fromResponder.authority)
        assertEquals(fixture.secureSessionId, fromInitiator.recordLayer.sessionId)
    }

    @Test
    fun theIssuedAuthorityIsSingleUse() {
        val fixture = PakeSessionFixture()
        val outcome = authenticated(
            runCoordinators(
                fixture.initiator(fixture.initiatorInteraction()),
                fixture.responder(fixture.responderInteraction()),
            ).first,
        )

        val claimed = requireNotNull(outcome.authority.claim()) { "the first claim must succeed" }
        assertEquals(outcome.recordLayer, claimed.recordLayer)
        assertNull("an authority is claimed exactly once", outcome.authority.claim())
        assertTrue(outcome.authority.isClaimed())
    }

    @Test
    fun payloadIsStillRefusedAfterACompletelySuccessfulPairing() {
        val fixture = PakeSessionFixture()
        val outcome = authenticated(
            runCoordinators(
                fixture.initiator(fixture.initiatorInteraction()),
                fixture.responder(fixture.responderInteraction()),
            ).first,
        )

        // The gate is not consulted by this path at all, and it still refuses unconditionally: an
        // authenticated control session is not payload authority.
        val decision = PayloadTransferGate.evaluate(outcome.controlSession)
        assertTrue(
            "payload must still be refused after control authentication",
            decision is PayloadTransferDecision.Refused,
        )
        assertEquals(
            SessionFailureCode.SECURE_SESSION_REQUIRED,
            (decision as PayloadTransferDecision.Refused).failure.code,
        )
    }

    // --- codes, retries, budget -----------------------------------------------------------

    @Test
    fun aWrongCodeIsRetriedAndTheSecondAttemptAuthenticates() {
        val fixture = PakeSessionFixture()
        val responderInteraction = quietInteraction(fixture.board, entered = ::wrongEntered)

        val (left, right) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(responderInteraction),
        )

        assertEquals(2, authenticated(left).attemptOrdinal)
        assertEquals(2, authenticated(right).attemptOrdinal)
        assertEquals(listOf(1, 2), responderInteraction.enteredOrdinals)
    }

    @Test
    fun threeWrongCodesEndInLimitReached() {
        val fixture = PakeSessionFixture()
        val responderInteraction = quietInteraction(fixture.board) { _, published -> wrongCode(published) }

        val (left, right) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(responderInteraction),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED, notAuthenticated(left).code)
        assertEquals(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED, notAuthenticated(right).code)
        assertEquals(3, notAuthenticated(left).attemptsCharged)
    }

    // --- approvals ------------------------------------------------------------------------

    @Test
    fun aLocalRejectionEndsTheRunWithoutAuthority() {
        val fixture = PakeSessionFixture()
        val initiatorInteraction = quietInteraction(
            fixture.board,
            approval = SecureApprovalDecision.REJECT,
        )

        val (left, right) = runCoordinators(
            fixture.initiator(initiatorInteraction),
            fixture.responder(fixture.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, notAuthenticated(left).code)
        assertTrue(right is PakePairingOutcome.Failed)
    }

    @Test
    fun aRemoteRejectionIsReportedToThePeerThatAsked() {
        val fixture = PakeSessionFixture()
        val responderInteraction = quietInteraction(
            fixture.board,
            approval = SecureApprovalDecision.REJECT,
        )

        val (left, right) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(responderInteraction),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, notAuthenticated(left).code)
        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, notAuthenticated(right).code)
    }

    @Test
    fun anUnansweredApprovalIsNeverAnApproval() {
        val fixture = PakeSessionFixture()
        val initiatorInteraction = quietInteraction(fixture.board, approval = null)

        val outcome = runCoordinators(
            fixture.initiator(initiatorInteraction),
            fixture.responder(fixture.responderInteraction()),
        ).first

        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED, notAuthenticated(outcome).code)
    }

    // --- the record layer and key confirmation --------------------------------------------

    @Test
    fun aRecordTheAeadRefusesIsNotAuthentication() {
        val fixture = PakeSessionFixture()
        // The responder's first protected record arrives with a bit flipped, which is what the
        // receiver sees when the two sides derived different record keys: the frame parses and the
        // AEAD refuses it. The PAKE has already confirmed by then, so the record layer is the only
        // thing standing between this peer and a false authenticated state.
        fixture.responderRecordIo.corruptNextFrame = true

        val (left, _) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(fixture.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, notAuthenticated(left).code)
    }

    @Test
    fun aMissingKeyConfirmationIsNotAuthentication() {
        val fixture = PakeSessionFixture()
        fixture.responderRecordIo.dropNextFrame = true

        val (left, _) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(fixture.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, notAuthenticated(left).code)
    }

    @Test
    fun aDuplicateKeyConfirmationFailsClosed() {
        val fixture = PakeSessionFixture()
        fixture.initiatorRecordIo.duplicateNextFrame = true

        val (_, right) = runCoordinators(
            fixture.initiator(fixture.initiatorInteraction()),
            fixture.responder(fixture.responderInteraction()),
        )

        // The extra copy is either refused as a second confirmation or never read because the run
        // already ended; either way it must not produce authentication on a replay.
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, notAuthenticated(right).code)
    }

    // --- cancellation and shutdown --------------------------------------------------------

    @Test
    fun aCancelledInteractionEndsWithoutAuthority() {
        val fixture = PakeSessionFixture()
        val interaction = fixture.initiatorInteraction().apply { cancelled = true }

        val outcome = fixture.initiator(interaction).run()

        assertEquals(SessionFailureCode.OPERATION_CANCELLED, notAuthenticated(outcome).code)
        assertEquals(0, notAuthenticated(outcome).attemptsCharged)
    }

    @Test
    fun closingACoordinatorResolvesItExactlyOnce() {
        val fixture = PakeSessionFixture()
        val coordinator = fixture.initiator(fixture.initiatorInteraction())

        coordinator.close()
        coordinator.close()

        assertEquals(SessionFailureCode.OPERATION_CANCELLED, notAuthenticated(coordinator.run()).code)
        assertEquals(1, fixture.initiatorIo.closes)
    }

    // --- concurrency ----------------------------------------------------------------------

    @Test
    fun twoSessionsRunConcurrentlyWithoutSharingState() {
        val first = PakeSessionFixture()
        val second = PakeSessionFixture(secureSessionId = SessionId("fedcba9876543210fedcba9876543210"))

        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val one = pool.submit(java.util.concurrent.Callable {
                runCoordinators(
                    first.initiator(first.initiatorInteraction()),
                    first.responder(first.responderInteraction()),
                )
            })
            val two = pool.submit(java.util.concurrent.Callable {
                runCoordinators(
                    second.initiator(second.initiatorInteraction()),
                    second.responder(second.responderInteraction()),
                )
            })
            val (oneLeft, oneRight) = one.get(180, java.util.concurrent.TimeUnit.SECONDS)
            val (twoLeft, twoRight) = two.get(180, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, authenticated(oneLeft).attemptOrdinal)
            assertEquals(1, authenticated(oneRight).attemptOrdinal)
            assertEquals(1, authenticated(twoLeft).attemptOrdinal)
            assertEquals(1, authenticated(twoRight).attemptOrdinal)
        } finally {
            pool.shutdownNow()
        }
    }

    // --- what the path refuses before it starts -------------------------------------------

    @Test
    fun aLegacySuiteCannotReachThePake() {
        val fixture = PakeSessionFixture()
        val legacy = legacySuiteOffer()
        val coordinator = PakeSessionCoordinator(
            channel = DeterministicPairingChannel(ByteArray(32) { 0x0A }),
            controlSession = fixture.controlSession(),
            role = SecurePeerRole.INITIATOR,
            clock = fixture.clock,
            entropy = CountingEntropy(),
            random = java.security.SecureRandom(),
            interaction = fixture.initiatorInteraction(),
            exchangeIo = fixture.initiatorExchangeIo,
            recordIo = fixture.initiatorRecordIo,
            initiatorOffer = legacy,
            responderOffer = legacy,
            transcriptDigestInput = ByteArray(32) { 0x66 },
        )

        assertEquals(
            SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID,
            notAuthenticated(coordinator.run()).code,
        )
        assertTrue(
            "a suite the required policy refuses must not write a single frame",
            fixture.initiatorExchangeIo.writtenLabels.isEmpty(),
        )
    }

    @Test
    fun aPeerThatDoesNotSpeakTls13CannotStart() {
        val fixture = PakeSessionFixture()
        val coordinator = PakeSessionCoordinator(
            channel = DeterministicPairingChannel(ByteArray(32) { 0x0A }, protocol = "TLSv1.2"),
            controlSession = fixture.controlSession(),
            role = SecurePeerRole.INITIATOR,
            clock = fixture.clock,
            entropy = CountingEntropy(),
            random = java.security.SecureRandom(),
            interaction = fixture.initiatorInteraction(),
            exchangeIo = fixture.initiatorExchangeIo,
            recordIo = fixture.initiatorRecordIo,
            initiatorOffer = fixture.initiatorOffer,
            responderOffer = fixture.responderOffer,
            transcriptDigestInput = ByteArray(32) { 0x66 },
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED, notAuthenticated(coordinator.run()).code)
        assertTrue(fixture.initiatorExchangeIo.writtenLabels.isEmpty())
    }

    private fun legacySuiteOffer(): PairingOfferV2 {
        val fixture = PakeSessionFixture()
        val base = fixture.initiatorOffer
        return PairingOfferV2(
            secureSessionId = base.secureSessionId,
            controlSessionId = base.controlSessionId,
            role = base.role,
            localPeerInstanceId = base.localPeerInstanceId,
            remotePeerInstanceId = base.remotePeerInstanceId,
            protocolMinimumVersion = base.protocolMinimumVersion,
            protocolMaximumVersion = base.protocolMaximumVersion,
            nonce = ByteArray(32) { 0x10 },
            certificateFingerprint = ByteArray(32) { 0x30 },
            transport = base.transport,
            securitySuiteWireId = base.securitySuiteWireId,
            tlsProtocol = base.tlsProtocol,
            tlsCipherSuite = base.tlsCipherSuite,
            maxRecordPlaintextBytes = base.maxRecordPlaintextBytes,
            maxRecordsPerDirection = base.maxRecordsPerDirection,
            maxBytesPerDirection = base.maxBytesPerDirection,
            sessionLifetimeMillis = base.sessionLifetimeMillis,
            offeredFeaturesMask = base.offeredFeaturesMask,
            offeredMaxChunkSizeBytes = base.offeredMaxChunkSizeBytes,
            offeredResumeSupported = base.offeredResumeSupported,
            offeredEncryption = base.offeredEncryption,
            offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            appVersion = base.appVersion,
            extensions = emptyList(),
        )
    }
}
