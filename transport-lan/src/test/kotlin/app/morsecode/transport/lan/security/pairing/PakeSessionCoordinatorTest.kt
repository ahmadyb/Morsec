/*
 * The composed chain, end to end on the JVM.
 *
 * The TLS exporter here is deterministic (see the harness header); everything else -- the PAKE
 * arithmetic, the frames, the exchange, the AES-GCM record layer, the key confirmations, the reducer
 * and the authority -- is production code. The real exporter and a real Conscrypt handshake are
 * exercised on a device by `PakeSessionApi23Test`.
 *
 * None of this shows the construction is secure. See doc/security/owner-risk-waiver-2026-10-19.md.
 * Nothing here is authority for payload.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SessionFailureCode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PakeSessionCoordinatorTest {

    @Test(timeout = 40_000L)
    fun anHonestRunAuthenticatesBothSides() {
        val link = PakeLink()
        val (left, right) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(link.responderInteraction()),
        )

        val initiator = authenticated(left)
        val responder = authenticated(right)
        assertNotNull(initiator.authority)
        assertNotNull(responder.authority)
        assertEquals(link.secureSessionId, initiator.recordLayer.sessionId)
        assertEquals(link.secureSessionId, responder.recordLayer.sessionId)
        assertEquals(PakePairingState.AUTHENTICATED, initiator.state)
    }

    @Test(timeout = 40_000L)
    fun theAuthorityIsSingleUse() {
        val link = PakeLink()
        val outcome = authenticated(
            runPairing(
                link.initiator(link.initiatorInteraction()),
                link.responder(link.responderInteraction()),
            ).first,
        )

        assertNotNull(outcome.authority.claim())
        assertNull("an authority is claimed exactly once", outcome.authority.claim())
    }

    @Test(timeout = 40_000L)
    fun payloadIsStillRefusedAfterControlAuthentication() {
        val link = PakeLink()
        val outcome = authenticated(
            runPairing(
                link.initiator(link.initiatorInteraction()),
                link.responder(link.responderInteraction()),
            ).first,
        )

        val decision = PayloadTransferGate.evaluate(outcome.authority.authenticatedControlSession)
        assertTrue("payload must still be refused", decision is PayloadTransferDecision.Refused)
        assertEquals(
            SessionFailureCode.SECURE_SESSION_REQUIRED,
            (decision as PayloadTransferDecision.Refused).failure.code,
        )
    }

    @Test(timeout = 40_000L)
    fun aWrongCodeIsRetriedAndTheSecondAttemptAuthenticates() {
        val link = PakeLink()
        val responderInteraction = quietInteraction(link.board, entered = ::wrongOnFirstAttempt)

        val (left, right) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(responderInteraction),
        )

        authenticated(left)
        authenticated(right)
        assertEquals(listOf(1, 2), responderInteraction.enteredOrdinals)
    }

    @Test(timeout = 40_000L)
    fun threeWrongCodesEndInLimitReached() {
        val link = PakeLink()
        val responderInteraction = quietInteraction(link.board, entered = { _, published ->
            wrongCode(published)
        })

        val (left, _) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(responderInteraction),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED, refused(left).reason)
        assertEquals(listOf(1, 2, 3), responderInteraction.enteredOrdinals)
    }

    @Test(timeout = 40_000L)
    fun aLocalRejectionEndsTheRunWithoutAuthority() {
        val link = PakeLink()
        val initiatorInteraction = quietInteraction(link.board, approval = SecureApprovalDecision.REJECT)

        val (left, _) = runPairing(
            link.initiator(initiatorInteraction),
            link.responder(link.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aRemoteRejectionEndsTheRunWithoutAuthority() {
        val link = PakeLink()
        val responderInteraction = quietInteraction(link.board, approval = SecureApprovalDecision.REJECT)

        val (left, _) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(responderInteraction),
        )

        // The initiator learns that its peer declined, not why or by whom: the record says REJECT and
        // binds to this exchange, and the refusal is the same one a local decline produces.
        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aRecordKeyMismatchIsNotAuthentication() {
        val link = PakeLink()
        val initiatorChannel = link.initiatorChannel(recordKeySalt = ByteArray(8) { 0x0B })

        val (left, _) = runPairing(
            link.initiatorWith(initiatorChannel, link.initiatorInteraction()),
            link.responder(link.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aReplayedConfirmationFrameIsRefusedByTheRecordLayer() {
        val link = PakeLink()
        val authenticatedOutcome = authenticated(
            runPairing(
                link.initiator(link.initiatorInteraction()),
                link.responder(link.responderInteraction()),
            ).first,
        )
        val delivered = link.initiatorRecordIo.framesDelivered
        assertEquals("exactly one confirmation crossed", 1, delivered.size)

        // The same bytes, a second time. The record layer refuses the repeated sequence rather than
        // letting a replay look like fresh evidence.
        val replay = authenticatedOutcome.recordLayer.unprotect(delivered.single().copyOf())
        assertTrue(replay is SecureRecordResult.Failed)
        assertEquals(
            SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            (replay as SecureRecordResult.Failed).failure.code,
        )
    }

    @Test(timeout = 40_000L)
    fun closingTheAuthorityReleasesOnceAndRevokesLaterClaims() {
        val link = PakeLink()
        val outcome = authenticated(
            runPairing(
                link.initiator(link.initiatorInteraction()),
                link.responder(link.responderInteraction()),
            ).first,
        )

        outcome.authority.close()
        outcome.authority.close()
        assertNull("a closed authority cannot be claimed", outcome.authority.claim())
        assertEquals("the record stream is released exactly once", 1, link.initiatorRecordIo.closeCount())
    }

    @Test(timeout = 40_000L)
    fun anUnansweredApprovalIsNeverAnApproval() {
        val link = PakeLink()
        val initiatorInteraction = quietInteraction(link.board, approval = null)

        val (left, _) = runPairing(
            link.initiator(initiatorInteraction),
            link.responder(link.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aRecordTheAeadRefusesIsNotAuthentication() {
        val link = PakeLink()
        link.responderRecordIo.corruptNextFrame = true

        val (left, _) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(link.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aMissingKeyConfirmationIsNotAuthentication() {
        val link = PakeLink()
        link.responderRecordIo.silent = true

        val (left, _) = runPairing(
            link.initiator(link.initiatorInteraction()),
            link.responder(link.responderInteraction()),
        )

        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, refused(left).reason)
    }

    @Test(timeout = 40_000L)
    fun aCancelledInteractionEndsWithoutAuthority() {
        val link = PakeLink()
        val interaction = link.initiatorInteraction().apply { cancelled = true }

        val outcome = link.initiator(interaction).run()

        assertEquals(SessionFailureCode.OPERATION_CANCELLED, refused(outcome).reason)
    }

    @Test(timeout = 40_000L)
    fun aLegacySuiteNeverReachesThePake() {
        val link = PakeLink()
        val initiatorLegacy = PairingOfferFactory.legacy(link, SecurePeerRole.INITIATOR)
        val responderLegacy = PairingOfferFactory.legacy(link, SecurePeerRole.RESPONDER)
        val outcome = PakeSessionCoordinator(
            channel = link.initiatorChannel(),
            controlSession = link.controlSession(),
            role = SecurePeerRole.INITIATOR,
            clock = link.clock,
            entropy = CountingEntropy(),
            random = java.security.SecureRandom(),
            interaction = link.initiatorInteraction(),
            initiatorOffer = initiatorLegacy,
            responderOffer = responderLegacy,
            transcriptDigest = link.transcriptDigest,
        ).run()

        assertEquals(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, refused(outcome).reason)
        assertTrue(
            "a refused suite must not write a frame",
            link.initiatorExchangeIo.writtenLabels.isEmpty(),
        )
    }

    @Test(timeout = 40_000L)
    fun aPeerThatDoesNotSpeakTls13CannotStart() {
        val link = PakeLink()
        val channel = DeterministicPairingChannel(
            secret = ByteArray(32) { 0x0A },
            exchange = link.initiatorExchangeIo,
            records = link.initiatorRecordIo,
            protocol = "TLSv1.2",
        )

        val outcome = link.initiatorWith(channel, link.initiatorInteraction()).run()

        assertEquals(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, refused(outcome).reason)
        assertTrue(link.initiatorExchangeIo.writtenLabels.isEmpty())
    }

    @Test(timeout = 40_000L)
    fun twoSessionsRunConcurrently() {
        val one = PakeLink()
        val two = PakeLink(secureSessionId = app.morsecode.core.transfer.identity.SessionId(
            "fedcba9876543210fedcba9876543210",
        ))
        val pool = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "pake-concurrent").apply { isDaemon = true }
        }
        try {
            val first = pool.submit(java.util.concurrent.Callable {
                runPairing(
                    one.initiator(one.initiatorInteraction()),
                    one.responder(one.responderInteraction()),
                )
            })
            val second = pool.submit(java.util.concurrent.Callable {
                runPairing(
                    two.initiator(two.initiatorInteraction()),
                    two.responder(two.responderInteraction()),
                )
            })
            val (a, b) = first.get(240, TimeUnit.SECONDS)
            val (c, d) = second.get(240, TimeUnit.SECONDS)
            authenticated(a)
            authenticated(b)
            authenticated(c)
            authenticated(d)
        } finally {
            pool.shutdownNow()
        }
    }
}

/** Builds one offer with a substituted suite, for the refusal cases only. */
internal object PairingOfferFactory {
    fun legacy(
        link: PakeLink,
        role: SecurePeerRole,
    ): PairingOfferV2 {
        val base = if (role == SecurePeerRole.INITIATOR) {
            link.initiatorOffer
        } else {
            link.responderOffer
        }
        return PairingOfferV2(
            secureSessionId = base.secureSessionId,
            controlSessionId = base.controlSessionId,
            role = base.role,
            localPeerInstanceId = base.localPeerInstanceId,
            remotePeerInstanceId = base.remotePeerInstanceId,
            protocolMinimumVersion = base.protocolMinimumVersion,
            protocolMaximumVersion = base.protocolMaximumVersion,
            nonce = base.nonceBytes(),
            certificateFingerprint = base.certificateFingerprintBytes(),
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
