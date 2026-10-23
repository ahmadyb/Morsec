package app.morsecode.transport.lan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePairingHelloV2Codec
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The single boundary at which a hostile peer is allowed to differ from production. */
internal enum class HostileMutation {
    /** Real production bytes, unmodified. Used as the control case. */
    NONE,
    LEGACY_MPS1_HELLO,
    CERTIFICATE_FINGERPRINT_SUBSTITUTED,
    PEER_INSTANCE_ID_SUBSTITUTED,
    CONTROL_SESSION_ID_SUBSTITUTED,
    ROLE_SWAPPED,
    NONCE_SUBSTITUTED,
    APP_VERSION_CHANGED,
    /// A peer that offers only the retired short-authentication-string comparison.
    AUTHENTICATION_SAS_ONLY,
    /// A peer that offers the "none" authentication capability.
    AUTHENTICATION_NONE,
    /// A peer that offers no authentication capability at all.
    AUTHENTICATION_ABSENT,
}

/**
 * A test-only crypto factory that is production in every respect except one method.
 *
 * Everything is delegated to [ConscryptPairingCryptoFactory]: real Conscrypt provider, real
 * ephemeral key, real self-signed certificate, real TLS 1.3 context, real handshake. The single
 * controlled boundary is [encodePairingHello], where the real production MPS2 codec is still used
 * to encode -- the offer it is handed has exactly one field altered, or the frame is replaced by a
 * legacy MPS1 payload.
 *
 * This class is `internal` to the `androidTest` source set. No `main` source can name it, and the
 * production coordinator omits the `cryptoFactory` argument entirely and so always receives
 * [ConscryptPairingCryptoFactory]. A static verifier pins both facts.
 */
internal class HostilePairingCryptoFactory(
    private val mutation: HostileMutation,
    private val random: SecureRandom,
) : PairingCryptoFactory {
    private val delegate = ConscryptPairingCryptoFactory(random)

    @Volatile
    var mutatedFrames: Int = 0
        private set

    override val securityProvider: Provider
        get() = delegate.securityProvider

    override fun generateEphemeralKeyPair(): KeyPair = delegate.generateEphemeralKeyPair()

    override fun constructEphemeralCertificate(keyPair: KeyPair): X509Certificate =
        delegate.constructEphemeralCertificate(keyPair)

    override fun createTlsContext(keyPair: KeyPair, certificate: X509Certificate): SSLContext =
        delegate.createTlsContext(keyPair, certificate)

    override fun startHandshake(sslSocket: SSLSocket) = delegate.startHandshake(sslSocket)

    override fun encodePairingHello(offer: PairingOfferV2): ByteArray {
        if (mutation == HostileMutation.NONE) return delegate.encodePairingHello(offer)
        mutatedFrames += 1
        if (mutation == HostileMutation.LEGACY_MPS1_HELLO) return legacyMps1Frame()
        // Still the production codec. Only the offer it is given has been altered.
        return SecurePairingHelloV2Codec.encodeFrame(alter(offer))
    }

    /**
     * A legacy MPS1 payload behind a valid MPS2 length header.
     *
     * The pairing path reads a fixed four-byte length prefix before it knows what it is holding, and
     * `decodeHeader` validates only that length. The payload's magic is what identifies the format,
     * so this is the shape that reaches the typed `LEGACY_MPS1_UNSUPPORTED` path rather than a
     * generic crypto failure. No MPS1 encoder is called, here or in production.
     */
    private fun legacyMps1Frame(): ByteArray {
        val payload = ByteArray(LEGACY_PAYLOAD_BYTES)
        MPS1_MAGIC.copyInto(payload)
        val out = ByteArray(4 + payload.size)
        out[0] = ((payload.size ushr 24) and 0xFF).toByte()
        out[1] = ((payload.size ushr 16) and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** Exactly one field differs from the offer production would have sent. */
    private fun alter(offer: PairingOfferV2): PairingOfferV2 = when (mutation) {
        HostileMutation.CERTIFICATE_FINGERPRINT_SUBSTITUTED -> copy(
            offer,
            fingerprint = offer.certificateFingerprintBytes().also { it[0] = (it[0].toInt() xor 0xFF).toByte() },
        )
        HostileMutation.PEER_INSTANCE_ID_SUBSTITUTED -> copy(
            offer,
            localPeerId = PeerInstanceId(SUBSTITUTED_PEER_ID),
        )
        HostileMutation.CONTROL_SESSION_ID_SUBSTITUTED -> copy(
            offer,
            controlSessionId = SessionId("substituted-control"),
        )
        HostileMutation.ROLE_SWAPPED -> copy(
            offer,
            role = if (offer.role == SecurePeerRole.INITIATOR) SecurePeerRole.RESPONDER else SecurePeerRole.INITIATOR,
        )
        HostileMutation.NONCE_SUBSTITUTED -> copy(
            offer,
            nonce = ByteArray(offer.nonceBytes().size).also { random.nextBytes(it) },
        )
        HostileMutation.APP_VERSION_CHANGED -> copy(offer, appVersion = "9.9.9-hostile")
        HostileMutation.NONE, HostileMutation.LEGACY_MPS1_HELLO -> offer
    }

    @Suppress("LongParameterList")
    private fun copy(
        offer: PairingOfferV2,
        nonce: ByteArray = offer.nonceBytes(),
        fingerprint: ByteArray = offer.certificateFingerprintBytes(),
        localPeerId: PeerInstanceId = offer.localPeerInstanceId,
        controlSessionId: SessionId = offer.controlSessionId,
        role: SecurePeerRole = offer.role,
        appVersion: String? = offer.appVersion,
        offeredAuthentication: PairingAuthenticationCapability? = offer.offeredAuthentication,
    ): PairingOfferV2 = PairingOfferV2(
        secureSessionId = offer.secureSessionId,
        controlSessionId = controlSessionId,
        role = role,
        localPeerInstanceId = localPeerId,
        remotePeerInstanceId = offer.remotePeerInstanceId,
        protocolMinimumVersion = offer.protocolMinimumVersion,
        protocolMaximumVersion = offer.protocolMaximumVersion,
        nonce = nonce,
        certificateFingerprint = fingerprint,
        transport = offer.transport,
        securitySuiteWireId = offer.securitySuiteWireId,
        tlsProtocol = offer.tlsProtocol,
        tlsCipherSuite = offer.tlsCipherSuite,
        maxRecordPlaintextBytes = offer.maxRecordPlaintextBytes,
        maxRecordsPerDirection = offer.maxRecordsPerDirection,
        maxBytesPerDirection = offer.maxBytesPerDirection,
        sessionLifetimeMillis = offer.sessionLifetimeMillis,
        offeredFeaturesMask = offer.offeredFeaturesMask,
        offeredMaxChunkSizeBytes = offer.offeredMaxChunkSizeBytes,
        offeredResumeSupported = offer.offeredResumeSupported,
        offeredEncryption = offer.offeredEncryption,
        offeredAuthentication = offeredAuthentication,
        appVersion = appVersion,
        extensions = offer.extensions,
    )

    private companion object {
        val MPS1_MAGIC = byteArrayOf(0x4D, 0x50, 0x53, 0x31)
        const val LEGACY_PAYLOAD_BYTES = 64
        const val SUBSTITUTED_PEER_ID = "fffffffffffffffffffffffffffffffe"
    }
}

private class HostileOutcome(
    val honest: SecurePairingRunResult,
    val board: Api23CodeBoard,
    val honestHuman: Api23Human,
    val hostileHuman: Api23Human,
    val hostileFactory: HostilePairingCryptoFactory,
    val honestSocket: Socket,
    val hostileFuture: Future<SecurePairingRunResult>,
)

/**
 * Hostile secure-session evidence on a real API 23 Conscrypt handshake.
 *
 * The honest endpoint is always the unmodified production coordinator with the production crypto
 * factory. The hostile endpoint is a second production coordinator whose only deviation is the
 * single encoding boundary in [HostilePairingCryptoFactory]. Nothing here weakens
 * `Conscrypt.isConscrypt`, substitutes a platform provider, trusts all certificates, relaxes a TLS
 * 1.3 check, or runs on Robolectric.
 *
 * DIAGNOSTICS. Assertion messages carry fixed test aliases and typed enum names only: no key
 * material, no shared secret, no transcript digest, no human proof text, no approval handle, no
 * certificate bytes, no frame contents, no full peer or session identifiers.
 */
@RunWith(AndroidJUnit4::class)
public class HostileSecureSessionApi23Test {
    @Test
    public fun unmutatedHostileFactoryStillAuthenticatesSoTheMutationsAreTheVariable() {
        val outcome = runPairing(HostileMutation.NONE)
        try {
            assertTrue(
                "the control case did not authenticate, so the mutations prove nothing",
                outcome.honest is SecurePairingRunResult.Authenticated,
            )
            assertEquals(0, outcome.hostileFactory.mutatedFrames)
            assertEquals(
                "the initiator displayed exactly one code",
                1,
                outcome.board.shownCount,
            )
            assertEquals(
                "the honest human answered the intended-peer question",
                1,
                outcome.honestHuman.approvalPrompts,
            )
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun legacyMps1HelloProducesATypedIncompatibleFormatResultAndNoApproval() {
        val outcome = runPairing(HostileMutation.LEGACY_MPS1_HELLO)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            val failed = outcome.honest as? SecurePairingRunResult.Failed
            assertTrue("a legacy MPS1 hello was not refused", failed != null)
            assertEquals(
                SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED,
                failed?.result?.failure?.code,
            )
            // No retry over MPS1, no plaintext fallback, no approval, no confirmation, no authority.
            assertEquals("an MPS1 refusal refused before a code existed", 0, outcome.board.shownCount)
            assertEquals("no approval may be asked for an MPS1 peer", 0, outcome.honestHuman.approvalPrompts)
            assertEquals(0, outcome.hostileHuman.approvalPrompts)
            assertTrue("the socket was left open after a legacy-format refusal", outcome.honestSocket.isClosed)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun helloFingerprintThatDiffersFromTheActualTlsCertificateFailsBeforeAnyApproval() {
        val outcome = runPairing(HostileMutation.CERTIFICATE_FINGERPRINT_SUBSTITUTED)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            val failed = outcome.honest as? SecurePairingRunResult.Failed
            assertTrue("a substituted certificate fingerprint was not refused", failed != null)
            assertEquals(
                SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED,
                failed?.result?.failure?.code,
            )
            // The direct fingerprint comparison runs before the transcript, so no code is ever
            // displayed and no approval is ever asked for.
            assertEquals("a fingerprint mismatch reached the code display", 0, outcome.board.shownCount)
            assertEquals(0, outcome.honestHuman.approvalPrompts)
            assertTrue(outcome.honestSocket.isClosed)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun substitutedPeerInstanceIdIsRefusedAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.PEER_INSTANCE_ID_SUBSTITUTED)
        try {
            val failed = outcome.honest as? SecurePairingRunResult.Failed
            assertTrue("a substituted peer instance id was not refused", failed != null)
            assertEquals(0, outcome.honestHuman.approvalPrompts)
            assertTrue(outcome.honestSocket.isClosed)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun substitutedControlSessionIdIsRefusedAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.CONTROL_SESSION_ID_SUBSTITUTED)
        try {
            assertTrue(
                "a substituted control session id was not refused",
                outcome.honest is SecurePairingRunResult.Failed,
            )
            assertEquals(0, outcome.honestHuman.approvalPrompts)
            assertTrue(outcome.honestSocket.isClosed)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun swappedRoleIsRefusedAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.ROLE_SWAPPED)
        try {
            assertTrue(
                "a swapped role was not refused",
                outcome.honest is SecurePairingRunResult.Failed,
            )
            assertEquals(0, outcome.honestHuman.approvalPrompts)
            assertTrue(outcome.honestSocket.isClosed)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun substitutedNonceChangesTheProofSoTheComparisonControllerRefusesRatherThanApproves() {
        val outcome = runPairing(HostileMutation.NONCE_SUBSTITUTED)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            // A substituted nonce is a well-formed offer, so both sides reach the PAKE. The
            // transcripts differ, so the two sides derive different keying material for the same
            // entered code; the first proof that fails is the PAKE tag, and neither side approves.
            assertTrue(
                "a substituted nonce still produced an authenticated session",
                outcome.honest !is SecurePairingRunResult.Authenticated,
            )
            assertEquals(
                "a transcript that never authenticated must not reach the intended-peer question",
                0,
                outcome.honestHuman.approvalPrompts,
            )
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun changedAppVersionAltersTheTranscriptAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.APP_VERSION_CHANGED)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            assertTrue(
                "a changed app version still produced an authenticated session",
                outcome.honest !is SecurePairingRunResult.Authenticated,
            )
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun aSasOnlyPeerIsRefusedBeforeAnyCodeIsDisplayed() {
        val outcome = runPairing(HostileMutation.AUTHENTICATION_SAS_ONLY)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            val failed = outcome.honest as? SecurePairingRunResult.Failed
            assertTrue("a SAS-only peer was not refused", failed != null)
            assertEquals(
                SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID,
                failed?.result?.failure?.code,
            )
            // The whole point of the switch: an SAS peer is refused, never compared against.
            assertEquals("a SAS-only peer was offered a code", 0, outcome.board.shownCount)
            assertEquals(0, outcome.honestHuman.approvalPrompts)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun anAuthenticationCapabilityOfNoneIsRefusedAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.AUTHENTICATION_NONE)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            assertTrue(
                "a peer offering no authentication was not refused",
                outcome.honest is SecurePairingRunResult.Failed,
            )
            assertEquals(0, outcome.board.shownCount)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    @Test
    public fun anAbsentAuthenticationCapabilityIsRefusedAndGrantsNoAuthority() {
        val outcome = runPairing(HostileMutation.AUTHENTICATION_ABSENT)
        try {
            assertEquals(1, outcome.hostileFactory.mutatedFrames)
            assertTrue(
                "a peer offering no authentication field was not refused",
                outcome.honest is SecurePairingRunResult.Failed,
            )
            assertEquals(0, outcome.board.shownCount)
            assertHostileEndpointGotNoAuthority(outcome)
        } finally {
            dispose(outcome)
        }
    }

    private fun assertHostileEndpointGotNoAuthority(outcome: HostileOutcome) {
        val hostile = try {
            outcome.hostileFuture.get(HOSTILE_SETTLE_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
        assertTrue(
            "the hostile endpoint reached an authenticated session",
            hostile !is SecurePairingRunResult.Authenticated,
        )
    }

    private fun runPairing(mutation: HostileMutation): HostileOutcome {
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = app.morsecode.core.transfer.session.SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        // A real, advancing clock: a frozen one would make every exchange deadline unreachable and
        // turn a refused pairing into a hung test.
        val clock = api23Clock()
        val random = SecureRandom()
        val board = Api23CodeBoard()
        // The honest endpoint always drives as initiator, so the hostile endpoint is the responder.
        // Both humans are honest: the mutation is in the MPS2 offer, never in the answers.
        val honestHuman = Api23Human(
            board = board,
            initiator = true,
            enteredCodes = emptyList(),
            approval = SecureApprovalDecision.APPROVE,
        )
        val hostileHuman = Api23Human(
            board = board,
            initiator = false,
            enteredCodes = listOf({ board.await(6_000L)?.toCharArray() }),
            approval = SecureApprovalDecision.APPROVE,
        )
        val hostileFactory = HostilePairingCryptoFactory(mutation, random)
        val hostileSession = session(
            localId = "00000000000000000000000000000002",
            remoteId = "00000000000000000000000000000001",
        )
        val honestSession = session(
            localId = "00000000000000000000000000000001",
            remoteId = "00000000000000000000000000000002",
        )
        val hostileFuture = executor.submit<SecurePairingRunResult> {
            val socket = listener.accept().apply {
                soTimeout = app.morsecode.core.transfer.session.SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            }
            SecureLanPairingCoordinator(
                rawSocket = socket,
                controlSession = hostileSession,
                role = SecurePeerRole.RESPONDER,
                clock = clock,
                random = random,
                interaction = hostileHuman,
                cryptoFactory = hostileFactory,
                exchangeBudgets = api23Budgets(),
                recordBudgets = api23RecordBudgets(),
            ).run()
        }
        val honestSocket = Socket()
        honestSocket.connect(
            listener.localSocketAddress,
            app.morsecode.core.transfer.session.SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS,
        )
        honestSocket.soTimeout = app.morsecode.core.transfer.session.SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        val honest = SecureLanPairingCoordinator(
            rawSocket = honestSocket,
            controlSession = honestSession,
            role = SecurePeerRole.INITIATOR,
            clock = clock,
            random = SecureRandom(),
            interaction = honestHuman,
            exchangeBudgets = api23Budgets(),
            recordBudgets = api23RecordBudgets(),
        ).run()
        listener.close()
        executor.shutdown()
        return HostileOutcome(
            honest = honest,
            board = board,
            honestHuman = honestHuman,
            hostileHuman = hostileHuman,
            hostileFactory = hostileFactory,
            honestSocket = honestSocket,
            hostileFuture = hostileFuture,
        )
    }

    private fun dispose(outcome: HostileOutcome) {
        (outcome.honest as? SecurePairingRunResult.Authenticated)?.authority?.close()
        try { outcome.honestSocket.close() } catch (_: Exception) { }
    }

    private fun session(localId: String, remoteId: String): NegotiatedSession = NegotiatedSession(
        attemptId = SessionId("attempt-1"),
        localProfile = profile(localId, "Local"),
        remoteProfile = profile(remoteId, "Remote"),
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

    private companion object {
        const val HOSTILE_SETTLE_SECONDS = 10L
    }
}
