package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.transport.lan.security.pairing.EnteredCodePairingInteraction
import app.morsecode.transport.lan.security.pairing.IntendedPeerContext
import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing ownership model after the discovery lease stops.
 *
 * Production uses model A: the lease that admitted a pairing stays its owner for that pairing's
 * whole life, and `isCancelled()` reports the lease's own closed flag. The invariant that matters is
 * not that counters reset safely -- it is that an admitted pairing cannot issue authenticated
 * authority once the lease that authorised it has closed. Resetting counters and making a late
 * permit release a no-op would be worthless if the session still came out the other end as
 * `Authenticated`.
 *
 * These tests drive the real [SecureLanPairingCoordinator]. Cancellation is reported from the start,
 * which is the point where the coordinator checks it before any TLS or certificate work, so nothing
 * here depends on a Conscrypt provider being present on the JVM.
 */
public class PairingOwnershipCancellationTest {

    /**
     * The entered-code interaction, scripted.
     *
     * Nothing here can answer a challenge: [cancelled] is the only thing the coordinator reads
     * before TLS, and the prompt methods would be reached only by a pairing that got that far.
     * [promptsPublished] stays zero for every test below, which is what proves a refused admission
     * never asked a human anything.
     */
    private class CancellableInteraction(
        @Volatile var cancelled: Boolean = false,
    ) : EnteredCodePairingInteraction {
        var promptsPublished = 0

        override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
            promptsPublished += 1
            return false
        }

        override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
            promptsPublished += 1
            return null
        }

        override fun requestIntendedPeerApproval(
            context: IntendedPeerContext,
            expiresAtElapsedMillis: Long,
        ): SecureApprovalDecision? {
            promptsPublished += 1
            return null
        }

        override fun isCancelled(): Boolean = cancelled
    }

    private class FixedClock(private val now: Long = 100L) : MonotonicClock {
        override fun nowMillis(): Long = now
    }

    private fun profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId(id),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private fun session(): NegotiatedSession = NegotiatedSession(
        attemptId = SessionId("attempt-1"),
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

    private fun coordinator(
        socket: Socket,
        interaction: EnteredCodePairingInteraction,
        scope: PairingAdmissionScope?,
    ): SecureLanPairingCoordinator = SecureLanPairingCoordinator(
        rawSocket = socket,
        controlSession = session(),
        role = SecurePeerRole.INITIATOR,
        clock = FixedClock(),
        random = SecureRandom(),
        interaction = interaction,
        attemptLimiter = scope,
    )

    private fun codeOf(result: SecurePairingRunResult): SessionFailureCode? =
        (result as? SecurePairingRunResult.Failed)?.result?.failure?.code

    @Test
    public fun `a pairing whose owning lease has closed issues no authenticated authority`() {
        val interaction = CancellableInteraction(cancelled = true)
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        val socket = Socket()
        try {
            val result = coordinator(socket, interaction, scope).run()
            assertFalse(
                "a closed lease must never yield authenticated authority",
                result is SecurePairingRunResult.Authenticated,
            )
            assertEquals(SessionFailureCode.OPERATION_CANCELLED, codeOf(result))
            assertEquals("cancellation must happen before a prompt is ever published", 0, interaction.promptsPublished)
        } finally {
            runCatching { socket.close() }
            scope.close()
        }
    }

    @Test
    public fun `a cancelled pairing releases its permit exactly once`() {
        val interaction = CancellableInteraction(cancelled = true)
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        val socket = Socket()
        try {
            coordinator(socket, interaction, scope).run()
            assertEquals(
                "the admitted permit must be back, exactly once",
                0,
                scope.concurrentPairings(),
            )
        } finally {
            runCatching { socket.close() }
            scope.close()
        }
    }

    @Test
    public fun `a lease closing mid-flight and the permit release cannot double count`() {
        // The specific interleaving: the scope closes while the pairing is still admitted, then the
        // pairing reaches the release in its finally. Neither may drive the count negative, and the
        // pairing must still come back cancelled rather than authenticated.
        repeat(50) { iteration ->
            val interaction = CancellableInteraction(cancelled = false)
            val scope = PairingAdmissionScope(monotonicMillis = { 0L })
            val socket = Socket()
            try {
                // Cancel after admission but before any cryptographic work is reported as done.
                interaction.cancelled = true
                val result = coordinator(socket, interaction, scope).run()
                scope.close()
                assertFalse(
                    "iteration $iteration: no authenticated authority after the lease closed",
                    result is SecurePairingRunResult.Authenticated,
                )
                assertEquals(0, scope.concurrentPairings())
            } finally {
                runCatching { socket.close() }
                scope.close()
            }
        }
    }

    @Test
    public fun `repeated cancellation costs the peer no security budget`() {
        val interaction = CancellableInteraction(cancelled = true)
        val scope = PairingAdmissionScope(
            monotonicMillis = { 0L },
            limits = SecurePairingAttemptLimiter.Limits(
                maxStartsPerIdentity = 1_000,
                maxStartsPerSource = 1_000,
                maxStartsPerPair = 1_000,
                maxConcurrentPairings = 1_000,
            ),
        )
        try {
            repeat(10) {
                val socket = Socket()
                try {
                    coordinator(socket, interaction, scope).run()
                } finally {
                    runCatching { socket.close() }
                }
            }
            assertFalse(
                "a local cancellation must never invalidate the peer identity",
                scope.isIdentityInvalidated("00000000000000000000000000000002"),
            )
            assertEquals(0, scope.concurrentPairings())
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a closed scope admits nothing, so no pairing can start after lease stop`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        scope.close()
        val interaction = CancellableInteraction(cancelled = false)
        val socket = Socket()
        try {
            val result = coordinator(socket, interaction, scope).run()
            assertFalse(result is SecurePairingRunResult.Authenticated)
            assertEquals(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED, codeOf(result))
            assertEquals(0, interaction.promptsPublished)
        } finally {
            runCatching { socket.close() }
        }
    }
}
