/*
 * The ordered proof path, driven one transition at a time.
 *
 * These tests are about what the reducer refuses. Each one asks for a transition that would be
 * convenient and asserts that it is impossible: approval before the PAKE, a remote approval standing
 * in for the local one, key confirmation standing in for the PAKE, a replay, a second authority.
 *
 * Nothing here is authority and nothing here establishes security; see
 * doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingSelectionV2
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PakeAttemptLedger
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PakePairingProgressionTest {

    @Test
    fun theHonestSequenceReachesAuthenticatedExactlyOnce() {
        val fixture = ProgressionFixture()
        assertTrue(fixture.progression.transcriptValidated(fixture.evidence()))
        assertTrue(fixture.progression.pakeConfirmed(fixture.confirmedEvidence()))
        assertTrue(fixture.progression.localApproved(SecureApprovalDecision.APPROVE))
        assertTrue(fixture.progression.remoteApproved(fixture.peerApproval()))
        assertTrue(fixture.progression.localKeyConfirmationSent())
        assertTrue(fixture.progression.remoteKeyConfirmationVerified(fixture.peerConfirmation()))
        assertTrue(fixture.progression.authenticated(true))
        assertEquals(PakePairingState.AUTHENTICATED, fixture.progression.state())
        assertFalse("the authority is issued once", fixture.progression.mayIssueAuthority())
        assertFalse(fixture.progression.authenticated(true))
    }

    @Test
    fun approvalBeforeThePakeIsRefused() {
        val fixture = ProgressionFixture()
        assertTrue(fixture.progression.transcriptValidated(fixture.evidence()))
        assertFalse(fixture.progression.localApproved(SecureApprovalDecision.APPROVE))
        assertEquals(PakePairingState.TRANSCRIPT_VALIDATED, fixture.progression.state())
    }

    @Test
    fun pakeConfirmationNeedsAValidatedTranscript() {
        val fixture = ProgressionFixture()
        assertFalse(fixture.progression.pakeConfirmed(fixture.confirmedEvidence()))
        assertEquals(PakePairingState.TRANSCRIPT_PENDING, fixture.progression.state())
    }

    @Test
    fun aRemoteApprovalCannotSubstituteForALocalOne() {
        val fixture = ProgressionFixture()
        fixture.advanceToPake()
        // No local decision yet: the remote record arrives first and must be refused.
        assertFalse(fixture.progression.remoteApproved(fixture.peerApproval()))
        assertEquals(PakePairingState.PAKE_CONFIRMED, fixture.progression.state())
    }

    @Test
    fun anApprovalForAnotherExchangeIsRefused() {
        val fixture = ProgressionFixture()
        fixture.advanceToLocalApproval()
        val foreign = fixture.peerApproval(transcript = ByteArray(32) { 0x7E })
        assertFalse(fixture.progression.remoteApproved(foreign))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test
    fun aReflectedApprovalIsRefusedBecauseTheRoleIsWrong() {
        val fixture = ProgressionFixture()
        fixture.advanceToLocalApproval()
        val reflected = fixture.peerApproval(role = SecurePeerRole.INITIATOR)
        assertFalse(fixture.progression.remoteApproved(reflected))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test
    fun aReplayedApprovalIsRefused() {
        val fixture = ProgressionFixture()
        fixture.advanceToLocalApproval()
        val approval = fixture.peerApproval()
        assertTrue(fixture.progression.remoteApproved(approval))
        // The second delivery of the same record must not produce a second transition.
        assertFalse(fixture.progression.remoteApproved(approval))
        assertEquals(PakePairingState.REMOTE_INTENDED_PEER_APPROVED, fixture.progression.state())
    }

    @Test
    fun keyConfirmationCannotStandInForThePake() {
        val fixture = ProgressionFixture()
        assertTrue(fixture.progression.transcriptValidated(fixture.evidence()))
        assertFalse(fixture.progression.localKeyConfirmationSent())
        assertFalse(fixture.progression.remoteKeyConfirmationVerified(fixture.peerConfirmation()))
        assertEquals(PakePairingState.TRANSCRIPT_VALIDATED, fixture.progression.state())
    }

    @Test
    fun aDuplicateKeyConfirmationFailsClosed() {
        val fixture = ProgressionFixture()
        fixture.advanceToLocalConfirmationSent()
        assertTrue(fixture.progression.remoteKeyConfirmationVerified(fixture.peerConfirmation()))
        assertFalse("a second confirmation cannot be ignored", fixture.progression.remoteKeyConfirmationVerified(
            fixture.peerConfirmation(),
        ))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, fixture.progression.failureCode())
    }

    @Test
    fun aConfirmationForTheWrongRoleIsRefused() {
        val fixture = ProgressionFixture()
        fixture.advanceToLocalConfirmationSent()
        val wrongRole = SecureKeyConfirmation(SecurePeerRole.INITIATOR, fixture.transcript)
        assertFalse(fixture.progression.remoteKeyConfirmationVerified(wrongRole))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test
    fun authenticationWithoutIssuedAuthorityIsAFailure() {
        val fixture = ProgressionFixture()
        fixture.advanceToRemoteVerification()
        assertFalse(fixture.progression.authenticated(false))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test
    fun cancellationIsTerminalAndWipesTheTranscript() {
        val fixture = ProgressionFixture()
        assertTrue(fixture.progression.transcriptValidated(fixture.evidence()))
        fixture.progression.cancel()
        assertEquals(PakePairingState.CANCELLED, fixture.progression.state())
        assertEquals(SessionFailureCode.OPERATION_CANCELLED, fixture.progression.failureCode())
        assertNull(fixture.progression.transcript())
        assertFalse(fixture.progression.pakeConfirmed(fixture.confirmedEvidence()))
    }

    @Test
    fun evidenceForAnotherSessionIsRefused() {
        val fixture = ProgressionFixture()
        val foreign = ValidatedTranscriptEvidence.from(
            fixture.transcript,
            fixture.selection,
            SessionId("ffffffffffffffffffffffffffffffff"),
        )
        assertNotNull(foreign)
        assertFalse(fixture.progression.transcriptValidated(requireNotNull(foreign)))
        assertEquals(PakePairingState.TRANSCRIPT_PENDING, fixture.progression.state())
    }

    @Test
    fun aSelectionForTheLegacySuiteProducesNoEvidence() {
        val fixture = ProgressionFixture()
        val legacy = PairingSelectionV2(
            selectedProtocolVersion = 2,
            selectedFeaturesMask = 9,
            selectedMaxChunkSizeBytes = 0L,
            selectedResumeSupported = false,
            selectedEncryption = app.morsecode.core.transfer.session.EncryptionCapability.TLS_1_3,
            selectedAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        assertNull(ValidatedTranscriptEvidence.from(fixture.transcript, legacy, fixture.secureSessionId))
    }

    @Test
    fun noPublicCallerCanBuildTransitionEvidence() {
        // Both evidence types have a private constructor and an internal factory. A public
        // constructor here would let any caller assert a transition it never performed. Kotlin
        // emits synthetic accessors for the companion's own use, so only the non-synthetic public
        // constructors are the ones a caller could actually reach.
        for (type in listOf(ValidatedTranscriptEvidence::class.java, ConfirmedPakeEvidence::class.java)) {
            val reachable = type.declaredConstructors.filter { constructor ->
                !constructor.isSynthetic &&
                    java.lang.reflect.Modifier.isPublic(constructor.modifiers)
            }
            assertTrue("$type must not be publicly constructible", reachable.isEmpty())
        }
    }

    /** One reducer plus a genuinely confirmed session for it to consume. */
    private class ProgressionFixture {
        val secureSessionId = SessionId("0123456789abcdef0123456789abcdef")
        val transcript = ByteArray(32) { 0x66 }
        val controlText = "ctl-session"
        val selection = PairingSelectionV2(
            selectedProtocolVersion = 2,
            selectedFeaturesMask = 9,
            selectedMaxChunkSizeBytes = 0L,
            selectedResumeSupported = false,
            selectedEncryption = app.morsecode.core.transfer.session.EncryptionCapability.TLS_1_3,
            selectedAuthentication = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
        )
        val progression = PakePairingProgression(secureSessionId, SecurePeerRole.INITIATOR, controlText)

        private val attempt: PakeAttempt = requireNotNull(
            PakeAttemptLedger(ByteArray(16) { 0x11 }).beginAttempt(SecureEntropy { destination ->
                destination.fill(0x11.toByte())
            }),
        )

        private val session: ModpJpakeSession = confirmedSession(attempt)

        fun evidence(): ValidatedTranscriptEvidence = requireNotNull(
            ValidatedTranscriptEvidence.from(transcript, selection, secureSessionId),
        )

        fun confirmedEvidence(): ConfirmedPakeEvidence = requireNotNull(
            ConfirmedPakeEvidence.from(transcript, SecurePeerRole.INITIATOR, session),
        )

        fun peerApproval(
            transcript: ByteArray = this.transcript,
            role: SecurePeerRole = SecurePeerRole.RESPONDER,
        ): PakeApproval = PakeApproval(
            decision = PakeApprovalDecision.APPROVE,
            suite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
            profile = JpakeCurveProfile.MODP_3072,
            role = role,
            secureSessionId = secureSessionId,
            controlSessionId = controlText,
            attemptId = attempt.id,
            attemptOrdinal = attempt.ordinal,
            codeInstanceId = attempt.codeInstanceBytes(),
            transcriptDigest = transcript,
        )

        fun peerConfirmation(): SecureKeyConfirmation =
            SecureKeyConfirmation(SecurePeerRole.RESPONDER, transcript)

        fun advanceToPake() {
            check(progression.transcriptValidated(evidence()))
            check(progression.pakeConfirmed(confirmedEvidence()))
        }

        fun advanceToLocalApproval() {
            advanceToPake()
            check(progression.localApproved(SecureApprovalDecision.APPROVE))
        }

        fun advanceToLocalConfirmationSent() {
            advanceToLocalApproval()
            check(progression.remoteApproved(peerApproval()))
            check(progression.localKeyConfirmationSent())
        }

        fun advanceToRemoteVerification() {
            advanceToLocalConfirmationSent()
            check(progression.remoteKeyConfirmationVerified(peerConfirmation()))
        }

        /**
         * One real exchange between two sessions sharing an attempt, so the confirmed session the
         * evidence carries is the production object and not a stand-in.
         */
        private fun confirmedSession(attempt: PakeAttempt): ModpJpakeSession {
            val ids = JpakeBinding.participantIds(ByteArray(32) { 0x11 })
            val initiator = ModpJpakeSession(
                SecurePeerRole.INITIATOR, attempt, ids, secureSessionId, controlText, "7KQF2XPD".toCharArray(),
            )
            val responder = ModpJpakeSession(
                SecurePeerRole.RESPONDER, attempt, ids, secureSessionId, controlText, "7KQF2XPD".toCharArray(),
            )
            val i1 = initiator.createRoundOne()
            val r1 = responder.createRoundOne()
            initiator.acceptRoundOne(r1)
            responder.acceptRoundOne(i1)
            val i2 = initiator.createRoundTwo()
            val r2 = responder.createRoundTwo()
            initiator.acceptRoundTwo(r2)
            responder.acceptRoundTwo(i2)
            val i3 = initiator.createConfirmation()
            val r3 = responder.createConfirmation()
            initiator.acceptConfirmation(r3)
            responder.acceptConfirmation(i3)
            responder.close()
            check(initiator.isConfirmed()) { "the fixture exchange must confirm" }
            return initiator
        }
    }
}
