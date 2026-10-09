package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.HumanVerificationCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureApprovalResult
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePairingState
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.core.transfer.session.SessionSecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lives in :transport-lan because the reducer is now `internal` to that module. That placement is
 * itself part of the M2 assertion: no module outside :transport-lan can construct this class, so no
 * external caller can drive a pairing to "authenticated".
 */
internal class SecureSessionStateMachineTest {

    @Test
    fun exactApprovalAndBothRoleSpecificConfirmationsAreRequired() {
        val clock = TestClock()
        val machine = machine(clock)
        val digest = transcriptDigest()
        val proof = proof()
        val request = requireNotNull(machine.requestApproval(digest, proof))
        assertEquals(SecurePairingState.AWAITING_LOCAL_APPROVAL, machine.state())
        assertTrue(request.toString().contains("[redacted]"))
        assertEquals("HumanVerificationCode([redacted])", request.proof.toString())

        assertEquals(SecureApprovalResult.Applied, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        assertEquals(SecurePairingState.APPROVED, machine.state())
        assertNull(machine.markLocalConfirmationSentAfterProtectedWrite())
        assertEquals(SecurePairingState.CONFIRMING, machine.state())
        assertNull(machine.completion())

        val peerConfirmation = confirmation(SecurePeerRole.RESPONDER, digest)
        val result = machine.receivePeerConfirmationFromAuthenticatedRecord(peerConfirmation)
        peerConfirmation.clearSensitive()

        // The completion is a status-only object: it hands back no session and therefore no
        // capability. This is the M2 authority change, asserted rather than assumed.
        assertSame(SecurePairingResult.Authenticated, result)
        assertEquals(SecurePairingState.AUTHENTICATED, machine.state())

        // The control session the reducer was built from never gains a secure marker.
        val control = controlSession()
        assertSame(SessionSecurityState.ControlOnlyUnauthenticated, control.security)
        assertTrue(PayloadTransferGate.evaluate(control) is PayloadTransferDecision.Refused)

        assertEquals("", request.proof.displayText())
        assertEquals(SecureApprovalResult.AlreadyApplied, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        digest.fill(0)
    }

    @Test
    fun aHandleFromAnotherPairingCannotApproveThisOne() {
        val machine = machine(TestClock())
        val digest = transcriptDigest()
        val request = requireNotNull(machine.requestApproval(digest, proof()))

        // A genuinely different pairing's handle is the adversarial case: it has the right type and
        // the right length, and only identity comparison rejects it.
        val stranger = machine(TestClock())
        val foreignRequest = requireNotNull(stranger.requestApproval(transcriptDigest(), proof()))
        assertEquals(SecureApprovalResult.Stale, machine.decide(foreignRequest.handle, SecureApprovalDecision.APPROVE))
        assertEquals(SecurePairingState.AWAITING_LOCAL_APPROVAL, machine.state())

        assertEquals(SecureApprovalResult.Applied, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        assertEquals(SecureApprovalResult.AlreadyApplied, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        assertEquals(SecureApprovalResult.Conflict, machine.decide(request.handle, SecureApprovalDecision.REJECT))
        assertEquals(SecurePairingState.APPROVED, machine.state())
        stranger.cancel()
        machine.cancel()
        digest.fill(0)
    }

    @Test
    fun peerConfirmationMayArriveBeforeApprovalButCannotBypassIt() {
        val machine = machine(TestClock())
        val digest = transcriptDigest()
        val request = requireNotNull(machine.requestApproval(digest, proof()))
        val peerConfirmation = confirmation(SecurePeerRole.RESPONDER, digest)
        assertNull(machine.receivePeerConfirmationFromAuthenticatedRecord(peerConfirmation))
        peerConfirmation.clearSensitive()
        assertEquals(SecurePairingState.AWAITING_LOCAL_APPROVAL, machine.state())
        assertNull(machine.completion())
        assertEquals(SecureApprovalResult.Applied, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        assertEquals(SecurePairingState.CONFIRMING, machine.state())
        val completion = machine.markLocalConfirmationSentAfterProtectedWrite()
        assertSame(SecurePairingResult.Authenticated, completion)
        assertEquals(SecurePairingState.AUTHENTICATED, machine.state())
        digest.fill(0)
    }

    @Test
    fun wrongRoleDigestDuplicateConfirmationAndMissingTranscriptFailClosed() {
        run {
            val machine = machine(TestClock())
            val digest = transcriptDigest()
            machine.requestApproval(digest, proof())
            val wrongRole = confirmation(SecurePeerRole.INITIATOR, digest)
            val failed = machine.receivePeerConfirmationFromAuthenticatedRecord(wrongRole) as SecurePairingResult.Failed
            assertEquals(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, failed.failure.code)
            wrongRole.clearSensitive()
            assertEquals(SecurePairingState.FAILED, machine.state())
            digest.fill(0)
        }
        run {
            val machine = machine(TestClock())
            val digest = transcriptDigest()
            machine.requestApproval(digest, proof())
            val wrongDigest = digest.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val wrong = confirmation(SecurePeerRole.RESPONDER, wrongDigest)
            assertTrue(
                machine.receivePeerConfirmationFromAuthenticatedRecord(wrong) is SecurePairingResult.Failed,
            )
            wrong.clearSensitive()
            wrongDigest.fill(0)
            digest.fill(0)
        }
        run {
            val machine = machine(TestClock())
            val digest = transcriptDigest()
            machine.requestApproval(digest, proof())
            val peer = confirmation(SecurePeerRole.RESPONDER, digest)
            assertNull(machine.receivePeerConfirmationFromAuthenticatedRecord(peer))
            assertTrue(
                machine.receivePeerConfirmationFromAuthenticatedRecord(peer) is SecurePairingResult.Failed,
            )
            peer.clearSensitive()
            digest.fill(0)
        }
    }

    @Test
    fun rejectionExpiryRollbackCancellationAndEntropyFailureNeverAuthenticate() {
        run {
            val machine = machine(TestClock())
            val request = requireNotNull(machine.requestApproval(transcriptDigest(), proof()))
            assertEquals(SecureApprovalResult.Applied, machine.decide(request.handle, SecureApprovalDecision.REJECT))
            val failed = machine.completion() as SecurePairingResult.Failed
            assertEquals(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, failed.failure.code)
            assertEquals(SecurePairingState.REJECTED, machine.state())
            assertEquals("", request.proof.displayText())
        }
        run {
            val clock = TestClock()
            val machine = machine(clock)
            val request = requireNotNull(machine.requestApproval(transcriptDigest(), proof()))
            clock.now = request.expiresAtElapsedMillis
            assertTrue(machine.expireIfNeeded() is SecurePairingResult.Failed)
            assertEquals(SecurePairingState.EXPIRED, machine.state())
            assertEquals(SecureApprovalResult.Expired, machine.decide(request.handle, SecureApprovalDecision.APPROVE))
        }
        run {
            val clock = TestClock()
            val machine = machine(clock)
            machine.requestApproval(transcriptDigest(), proof())
            clock.now = -1L
            val failed = machine.expireIfNeeded() as SecurePairingResult.Failed
            assertEquals(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED, failed.failure.code)
            assertEquals(SecurePairingState.FAILED, machine.state())
        }
        run {
            val machine = machine(TestClock())
            val request = requireNotNull(machine.requestApproval(transcriptDigest(), proof()))
            assertTrue(machine.cancel() is SecurePairingResult.Failed)
            assertEquals(SecurePairingState.CANCELLED, machine.state())
            assertEquals("", request.proof.displayText())
        }
        run {
            val broken = SecureSessionStateMachine(
                controlSession = controlSession(),
                secureSessionId = SECURE_ID,
                localRole = SecurePeerRole.INITIATOR,
                clock = TestClock(),
                entropy = SecureEntropy { throw IllegalStateException("secret provider detail") },
            )
            val failed = broken.requestApproval(transcriptDigest(), proof())
            assertNull(failed)
            assertEquals(SecurePairingState.FAILED, broken.state())
            assertEquals(
                SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED,
                (broken.completion() as SecurePairingResult.Failed).failure.code,
            )
        }
    }

    @Test
    fun noStateAdvancesAfterTheReducerHasCompleted() {
        val machine = machine(TestClock())
        val digest = transcriptDigest()
        val request = requireNotNull(machine.requestApproval(digest, proof()))
        machine.decide(request.handle, SecureApprovalDecision.APPROVE)
        val peerConfirmation = confirmation(SecurePeerRole.RESPONDER, digest)
        machine.receivePeerConfirmationFromAuthenticatedRecord(peerConfirmation)
        machine.markLocalConfirmationSentAfterProtectedWrite()
        assertSame(SecurePairingResult.Authenticated, machine.completion())

        // A replayed confirmation must not re-run completion or flip the phase.
        val replay = confirmation(SecurePeerRole.RESPONDER, digest)
        assertSame(SecurePairingResult.Authenticated, machine.receivePeerConfirmationFromAuthenticatedRecord(replay))
        replay.clearSensitive()
        assertEquals(SecurePairingState.AUTHENTICATED, machine.state())
        assertSame(SecurePairingResult.Authenticated, machine.markLocalConfirmationSentAfterProtectedWrite())
        assertSame(SecurePairingResult.Authenticated, machine.cancel())
        digest.fill(0)
    }

    private fun machine(clock: TestClock): SecureSessionStateMachine = SecureSessionStateMachine(
        controlSession = controlSession(),
        secureSessionId = SECURE_ID,
        localRole = SecurePeerRole.INITIATOR,
        clock = clock,
        entropy = SecureEntropy { destination -> destination.indices.forEach { destination[it] = (it + 1).toByte() } },
    )

    private fun controlSession(): NegotiatedSession {
        val local = profile("00000000000000000000000000000001", "Local")
        val remote = profile("00000000000000000000000000000002", "Remote")
        return NegotiatedSession(
            attemptId = SessionId("attempt-1"),
            localProfile = local,
            remoteProfile = remote,
            protocolVersion = 1,
            capabilities = NegotiatedCapabilities(
                features = setOf(SessionFeature.CONTROL_HANDSHAKE),
                maxChunkSizeBytes = 4_096,
                resumeSupported = false,
                encryption = EncryptionCapability.NONE,
            ),
        )
    }

    private fun profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId(id),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private fun transcriptDigest(): ByteArray = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 3).toByte() }

    private fun proof(): HumanVerificationCode =
        HumanVerificationCode.fromExporterMaterial(ByteArray(32) { (it + 1).toByte() })

    private fun confirmation(role: SecurePeerRole, digest: ByteArray): SecureKeyConfirmation =
        SecureKeyConfirmation(role, digest)

    private class TestClock(var now: Long = 100L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }

    private companion object {
        val SECURE_ID = SessionId("00112233445566778899aabbccddeeff")
    }
}
