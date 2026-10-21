package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PakeAttemptLedger
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.core.transfer.session.SessionSecurityState
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.io.Closeable
import java.lang.reflect.Modifier
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authority boundary for authenticated control.
 *
 * The fixture is one genuinely completed MODP exchange rather than a set of booleans, because the
 * whole point of the type is that it takes objects and not claims. That makes these tests slower than
 * the surrounding suites and considerably more meaningful than a constructor-argument test.
 */
internal class AuthenticatedControlAuthorityTest {

    @Test
    fun aCompletedExchangeIssuesAOneShotAuthority() {
        val fixture = fixture()
        val authority = requireNotNull(issue(fixture)) { "the honest fixture must issue" }
        val claimed = requireNotNull(authority.claim()) { "the first claim must succeed" }
        assertEquals(fixture.recordLayer, claimed.recordLayer)
        assertNull("an authority is claimed exactly once", authority.claim())
        assertTrue(authority.isClaimed())
        authority.close()
        assertTrue(authority.isClosed())
        assertTrue(authority.toString().contains("[redacted]"))
    }

    @Test
    fun aClosedAuthorityCannotBeClaimed() {
        val fixture = fixture()
        val authority = requireNotNull(issue(fixture))
        authority.close()
        assertNull(authority.claim())
    }

    @Test
    fun anythingOtherThanTls13IsRefused() {
        val fixture = fixture()
        for (protocol in listOf("TLSv1.2", "TLSv1.1", "", "TLSv1.3-draft")) {
            assertNull(
                "a $protocol channel must not authenticate",
                issue(fixture, tlsProtocol = protocol),
            )
        }
    }

    @Test
    fun aTranscriptDigestOfTheWrongWidthIsRefused() {
        val fixture = fixture()
        assertNull(issue(fixture, transcriptDigest = ByteArray(31)))
        assertNull(issue(fixture, transcriptDigest = ByteArray(33)))
    }

    @Test
    fun bothApprovalsMustBeApprove() {
        val fixture = fixture()
        assertNull("no local decision is not approval", issue(fixture, localDecision = null))
        assertNull(
            "a local rejection must not issue authority",
            issue(fixture, localDecision = SecureApprovalDecision.REJECT),
        )
        assertNull("no remote approval is not approval", issue(fixture, remoteApproval = null))
        assertNull(
            "a remote rejection must not issue authority",
            issue(fixture, remoteApproval = fixture.peerApproval(decision = PakeApprovalDecision.REJECT)),
        )
    }

    @Test
    fun theRemoteApprovalMustNameThisExactExchange() {
        val fixture = fixture()
        assertNull(
            "an approval for another attempt must be refused",
            issue(fixture, remoteApproval = fixture.peerApproval(attempt = fixture.otherAttempt())),
        )
        assertNull(
            "an approval from another session must be refused",
            issue(
                fixture,
                remoteApproval = fixture.peerApproval(
                    secureSessionId = SessionId("ffffffffffffffffffffffffffffffff"),
                ),
            ),
        )
        assertNull(
            "an approval over another transcript must be refused",
            issue(fixture, remoteApproval = fixture.peerApproval(transcript = ByteArray(32) { 0x7F })),
        )
        assertNull(
            "a reflected approval names our own role, not the peer's",
            issue(fixture, remoteApproval = fixture.peerApproval(role = SecurePeerRole.INITIATOR)),
        )
        assertNull(
            "an approval for another control session must be refused",
            issue(fixture, remoteApproval = fixture.peerApproval(controlSessionId = "ctl-other")),
        )
    }

    @Test
    fun thePakeMustBeConfirmedForThisAttemptAndRole() {
        val fixture = fixture()
        assertNull(
            "a session belonging to another attempt proves nothing about this one",
            issue(fixture, pakeSession = fixture.unrelatedSession()),
        )
        assertNull(
            "the local role must match the session's role",
            issue(fixture, role = SecurePeerRole.RESPONDER),
        )
        assertNull(
            "a settled attempt cannot issue a second authority",
            run {
                val isolated = isolatedFixture()
                isolated.attempt.succeed()
                issue(isolated, attempt = isolated.attempt)
            },
        )
    }

    @Test
    fun anExpiredSessionIssuesNothing() {
        val fixture = fixture()
        assertNull(
            "a pairing older than the session lifetime must not issue authority",
            issue(fixture, nowElapsedMillis = NOW + SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS),
        )
        assertNull(
            "a clock that went backwards is a refusal, not a fresh lifetime",
            issue(fixture, nowElapsedMillis = NOW - 1),
        )
        assertNotNull(issue(fixture, nowElapsedMillis = NOW + 1_000L))
    }

    @Test
    fun theAuthorityHasNoPublicConstructor() {
        val constructors = AuthenticatedControlAuthority::class.java.declaredConstructors
        assertTrue("the type must declare a constructor", constructors.isNotEmpty())
        assertTrue(
            "every constructor must be private, or a caller can mint authority",
            constructors.all { Modifier.isPrivate(it.modifiers) },
        )
        val channelConstructors =
            AuthenticatedControlAuthority.AuthenticatedControlChannel::class.java.declaredConstructors
        assertTrue(
            "the claimed channel must not be constructible by a caller",
            channelConstructors.isNotEmpty() && channelConstructors.all { Modifier.isPrivate(it.modifiers) },
        )
    }

    @Test
    fun theAuthorityConfersNoPayloadPermission() {
        val fixture = fixture()
        val authority = requireNotNull(issue(fixture))
        assertNotNull(authority.claim())
        // Authenticated control does not move the payload gate: it still refuses unconditionally.
        assertTrue(
            PayloadTransferGate.evaluate(fixture.controlSession) is PayloadTransferDecision.Refused,
        )
        assertTrue(
            "the control session keeps its unauthenticated marker",
            fixture.controlSession.security == SessionSecurityState.ControlOnlyUnauthenticated,
        )
        assertFalse(
            SessionFeature.FILE_PAYLOAD in fixture.controlSession.capabilities.features,
        )
    }

    // --- fixture ------------------------------------------------------------------------

    private fun issue(
        fixture: Fixture,
        tlsProtocol: String = TLS_13,
        transcriptDigest: ByteArray = fixture.transcriptDigest,
        attempt: PakeAttempt = fixture.attempt,
        pakeSession: ModpJpakeSession = fixture.pakeSession,
        localDecision: SecureApprovalDecision? = SecureApprovalDecision.APPROVE,
        remoteApproval: PakeApproval? = fixture.peerApproval(),
        role: SecurePeerRole = SecurePeerRole.INITIATOR,
        pairStartedElapsedMillis: Long = NOW,
        nowElapsedMillis: Long = NOW,
    ): AuthenticatedControlAuthority? = AuthenticatedControlAuthority.issue(
        channel = fixture.channel,
        recordLayer = fixture.recordLayer,
        controlSession = fixture.controlSession,
        tlsProtocol = tlsProtocol,
        transcriptDigest = transcriptDigest,
        attempt = attempt,
        pakeSession = pakeSession,
        localDecision = localDecision,
        remoteApproval = remoteApproval,
        role = role,
        pairStartedElapsedMillis = pairStartedElapsedMillis,
        nowElapsedMillis = nowElapsedMillis,
    )

    private fun fixture(): Fixture = buildFixture()

    private fun buildFixture(): Fixture {
        val ids = JpakeBinding.participantIds(ByteArray(32) { 0x11 })
        val attempt = requireNotNull(
            PakeAttemptLedger(ByteArray(16) { 0x11 }).beginAttempt(SecureEntropy { it.fill(0x11.toByte()) }),
        )
        val initiator = ModpJpakeSession(
            role = SecurePeerRole.INITIATOR,
            attempt = attempt,
            participantIds = ids,
            secureSessionId = SECURE_ID,
            controlSessionId = CONTROL_ID,
            code = CODE.toCharArray(),
        )
        val responder = ModpJpakeSession(
            role = SecurePeerRole.RESPONDER,
            attempt = attempt,
            participantIds = ids,
            secureSessionId = SECURE_ID,
            controlSessionId = CONTROL_ID,
            code = CODE.toCharArray(),
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

        val recordLayer = SecureRecordLayer(
            sessionId = SECURE_ID,
            localRole = SecurePeerRole.INITIATOR,
            sendAead = JcaAesGcmAead(ByteArray(32) { 1 }),
            receiveAead = JcaAesGcmAead(ByteArray(32) { 2 }),
            sendNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 3 },
            receiveNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 4 },
            clock = TestClock(),
        )
        return Fixture(
            attempt = attempt,
            pakeSession = initiator,
            recordLayer = recordLayer,
            controlSession = controlSession(),
            transcriptDigest = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 5).toByte() },
            channel = Closeable { },
        )
    }

    private fun controlSession(): NegotiatedSession = NegotiatedSession(
        attemptId = SessionId(CONTROL_ID),
        localProfile = profile("00000000000000000000000000000001", "Local"),
        remoteProfile = profile("00000000000000000000000000000002", "Remote"),
        protocolVersion = 1,
        capabilities = NegotiatedCapabilities(
            features = setOf(SessionFeature.CONTROL_HANDSHAKE),
            maxChunkSizeBytes = 4_096,
            resumeSupported = false,
            encryption = EncryptionCapability.NONE,
        ),
    )

    private fun profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId(id),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private class Fixture(
        val attempt: PakeAttempt,
        val pakeSession: ModpJpakeSession,
        val recordLayer: SecureRecordLayer,
        val controlSession: NegotiatedSession,
        val transcriptDigest: ByteArray,
        val channel: Closeable,
    ) {
        fun peerApproval(
            decision: PakeApprovalDecision = PakeApprovalDecision.APPROVE,
            attempt: PakeAttempt = this.attempt,
            secureSessionId: SessionId = recordLayer.sessionId,
            transcript: ByteArray = transcriptDigest,
            role: SecurePeerRole = SecurePeerRole.RESPONDER,
            controlSessionId: String = controlSession.attemptId.value,
        ): PakeApproval = PakeApproval(
            decision = decision,
            suite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
            profile = JpakeCurveProfile.MODP_3072,
            role = role,
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            attemptId = attempt.id,
            attemptOrdinal = attempt.ordinal,
            codeInstanceId = attempt.codeInstanceBytes(),
            transcriptDigest = transcript,
        )

        /** A second, independent attempt: its own ledger, its own identifiers, never confirmed. */
        fun otherAttempt(): PakeAttempt = PakeAttemptLedger(ByteArray(16) { 0x66 })
            .beginAttempt(SecureEntropy { it.fill(0x66.toByte()) })
            .let { requireNotNull(it) }

        fun unrelatedSession(): ModpJpakeSession =
            ModpJpakeSession(
                role = SecurePeerRole.INITIATOR,
                attempt = otherAttempt(),
                participantIds = JpakeBinding.participantIds(ByteArray(32) { 0x33 }),
                secureSessionId = recordLayer.sessionId,
                controlSessionId = controlSession.attemptId.value,
                code = CODE.toCharArray(),
            )
    }

    private class TestClock : MonotonicClock {
        override fun nowMillis(): Long = NOW
    }

    private class JcaAesGcmAead(key: ByteArray) : SecureRecordAead {
        private val delegate = Cipher.getInstance("AES/GCM/NoPadding")
        private val spec = SecretKeySpec(key, "AES")

        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
            delegate.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
            delegate.updateAAD(associatedData)
            return delegate.doFinal(plaintext)
        }

        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? {
            return try {
                delegate.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce))
                delegate.updateAAD(associatedData)
                delegate.doFinal(ciphertext)
            } catch (_: Exception) {
                null
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val TLS_13 = "TLSv1.3"
        const val CONTROL_ID = "ctl-session"
        const val CODE = "7KQF2XPD"
        const val NOW = 1_000L
        val SECURE_ID = SessionId("0123456789abcdef0123456789abcdef")
    }
}
