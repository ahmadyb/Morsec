/*
 * The ordered reducer, one transition at a time.
 *
 * Each test asks for a step the protocol forbids and asserts that the pairing fails closed. The
 * point is not that the method returns false -- it is that the state cannot be reached at all, so
 * there is no code path afterwards that could mistake a refusal for a success.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PakePairingProgressionTest {

    @Test(timeout = 40_000L)
    fun theHonestSequenceReachesAuthenticationExactlyOnce() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        assertTrue(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertTrue(fixture.approved())
        assertTrue(fixture.progression.localKeyConfirmationSent(ByteArray(32) { 1 }))
        assertTrue(fixture.verified())
        val transition = requireNotNull(fixture.progression.authenticated())
        assertEquals(PakePairingState.AUTHENTICATED, fixture.progression.state())
        assertTrue(fixture.progression.authorityWasIssued())
        assertNull("a second authority transition is impossible", fixture.progression.authenticated())
        assertEquals(fixture.attempt, transition.attempt)
    }

    @Test(timeout = 40_000L)
    fun approvalBeforeThePakeFailsClosed() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertFalse(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun thePairedApprovalCannotStandInForTheLocalOne() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        // The peer's record arrives before this side has asked its human.
        assertFalse(fixture.progression.remoteIntendedPeerApproved(fixture.peerApproval()))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun aConfirmationCannotStandInForThePake() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertFalse(fixture.progression.remoteKeyConfirmationVerified(fixture.peerConfirmation()))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun authenticationBeforeKeyConfirmationReturnsNothing() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        assertTrue(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertTrue(fixture.approved())
        assertNull(fixture.progression.authenticated())
        assertEquals(PakePairingState.REMOTE_INTENDED_PEER_APPROVED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun aReplayedApprovalStopsAtTheStateItCameFrom() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        assertTrue(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertTrue(fixture.approved())
        // The same record again: the transition was already consumed, so this is out of order.
        assertFalse(fixture.progression.remoteIntendedPeerApproved(fixture.peerApproval()))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun anApprovalForAnotherTranscriptIsRefused() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        assertTrue(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertFalse(
            fixture.progression.remoteIntendedPeerApproved(
                fixture.peerApproval(transcript = ByteArray(32) { 0x5A }),
            ),
        )
        assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun anApprovalForTheSameRoleIsRefused() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.confirmed())
        assertTrue(fixture.progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
        assertFalse(fixture.progression.remoteIntendedPeerApproved(fixture.peerApproval(role = fixture.role)))
    }

    @Test(timeout = 40_000L)
    fun aSecondConfirmationCannotBeVerified() {
        val fixture = Fixture()
        fixture.reachLocalConfirmationSent()
        assertTrue(fixture.verified())
        assertFalse(fixture.progression.remoteKeyConfirmationVerified(fixture.peerConfirmation()))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    @Test(timeout = 40_000L)
    fun noPublicCallerCanBuildTransitionEvidence() {
        // The handover is not a token: it is only constructible inside this module, and the class it
        // carries is not public. Authority is re-derived downstream, so a hand-built transition
        // would have to smuggle a genuinely confirmed session to be accepted.
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                AuthenticatedTransition::class.java.modifiers,
            ),
        )
        val constructors = AuthenticatedTransition::class.java.declaredConstructors
            .filter { !it.isSynthetic }
        assertTrue("a transition has exactly one declared constructor", constructors.size == 1)
        assertFalse(
            "no declared constructor may be public",
            java.lang.reflect.Modifier.isPublic(constructors.single().modifiers),
        )
    }

    @Test(timeout = 40_000L)
    fun aLegacySelectionNeverValidates() {
        val fixture = Fixture()
        assertFalse(
            fixture.progression.transcriptValidated(
                PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
                EncryptionCapability.TLS_1_3,
            ),
        )
        assertEquals(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun aConfirmationFromTheWrongRoleFails() {
        val fixture = Fixture()
        fixture.reachLocalConfirmationSent()
        assertFalse(
            fixture.progression.remoteKeyConfirmationVerified(
                SecureKeyConfirmation(fixture.role, fixture.digest),
            ),
        )
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun aConfirmationForAnotherTranscriptFails() {
        val fixture = Fixture()
        fixture.reachLocalConfirmationSent()
        assertFalse(
            fixture.progression.remoteKeyConfirmationVerified(
                SecureKeyConfirmation(fixture.role.opposite, ByteArray(32) { 0x33 }),
            ),
        )
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun aConfirmationOfTheWrongWidthCannotBeSent() {
        val fixture = Fixture()
        fixture.reachRemoteApproval()
        assertFalse(fixture.progression.localKeyConfirmationSent(ByteArray(31)))
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun anUnconfirmedSessionIsRefused() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        val ids = JpakeBinding.participantIds(ByteArray(32) { 0x11 })
        val halfDone = ModpJpakeSession(
            SecurePeerRole.INITIATOR,
            fixture.attempt,
            ids,
            SessionId("0123456789abcdef0123456789abcdef"),
            "ctl-session",
            "7KQF2XPD".toCharArray(),
        )
        halfDone.createRoundOne()
        assertFalse(fixture.progression.pakeConfirmed(fixture.attempt, halfDone))
        assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, fixture.progression.failureCode())
    }

    @Test(timeout = 40_000L)
    fun aSettledAttemptIsRefused() {
        val fixture = Fixture()
        assertTrue(fixture.validated())
        assertTrue(fixture.attempt.succeed())
        assertFalse(fixture.progression.pakeConfirmed(fixture.attempt, fixture.session))
        assertEquals(PakePairingState.FAILED, fixture.progression.state())
    }

    /** One reducer plus a genuinely confirmed PAKE session for it to consume. */
    private class Fixture(
        secureSessionId: SessionId = SessionId("0123456789abcdef0123456789abcdef"),
        val role: SecurePeerRole = SecurePeerRole.INITIATOR,
    ) {
        val digest = ByteArray(32) { 0x66 }
        private val controlSessionId = "ctl-session"
        val progression = PakePairingProgression(secureSessionId, controlSessionId, role, digest)
        val attempt: PakeAttempt = requireNotNull(
            PakeAttemptLedger(ByteArray(16) { 0x11 }).beginAttempt(
                SecureEntropy { destination -> destination.fill(0x11.toByte()) },
            ),
        )
        val session: ModpJpakeSession = confirm(secureSessionId, controlSessionId, attempt)

        fun validated(): Boolean = progression.transcriptValidated(
            PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
            EncryptionCapability.TLS_1_3,
        )

        fun confirmed(): Boolean = progression.pakeConfirmed(attempt, session)

        fun approved(): Boolean = progression.remoteIntendedPeerApproved(peerApproval())

        fun verified(): Boolean =
            progression.remoteKeyConfirmationVerified(SecureKeyConfirmation(role.opposite, digest))

        fun reachLocalConfirmationSent() {
            check(validated())
            check(confirmed())
            check(progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
            check(approved())
            check(progression.localKeyConfirmationSent(ByteArray(32) { 2 }))
        }

        fun reachRemoteApproval() {
            check(validated())
            check(confirmed())
            check(progression.localIntendedPeerApproved(SecureApprovalDecision.APPROVE))
            check(approved())
        }

        fun peerApproval(
            transcript: ByteArray = digest,
            role: SecurePeerRole = this.role.opposite,
        ): PakeApproval = PakeApproval(
            decision = PakeApprovalDecision.APPROVE,
            suite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
            profile = JpakeCurveProfile.MODP_3072,
            role = role,
            secureSessionId = SessionId("0123456789abcdef0123456789abcdef"),
            controlSessionId = controlSessionId,
            attemptId = attempt.id,
            attemptOrdinal = attempt.ordinal,
            codeInstanceId = attempt.codeInstanceBytes(),
            transcriptDigest = transcript,
        )

        fun peerConfirmation(): SecureKeyConfirmation =
            SecureKeyConfirmation(role.opposite, digest)

        private fun confirm(
            secureSessionId: SessionId,
            controlSessionId: String,
            attempt: PakeAttempt,
        ): ModpJpakeSession {
            val ids = JpakeBinding.participantIds(ByteArray(32) { 0x11 })
            val initiator = ModpJpakeSession(
                SecurePeerRole.INITIATOR, attempt, ids, secureSessionId, controlSessionId, "7KQF2XPD".toCharArray(),
            )
            val responder = ModpJpakeSession(
                SecurePeerRole.RESPONDER, attempt, ids, secureSessionId, controlSessionId, "7KQF2XPD".toCharArray(),
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
