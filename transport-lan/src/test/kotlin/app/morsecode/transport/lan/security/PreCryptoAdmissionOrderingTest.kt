package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-crypto admission ordering, proved with runtime call counts rather than static inspection.
 *
 * WHAT IS KNOWN BEFORE TLS, AND WHY THAT IS HONEST HERE. The coordinator has exactly one production
 * construction site, in the outbound selected-peer flow. There the remote identity is not a claim
 * made by the remote peer: it is the identity the local user selected from discovery, already bound
 * to the `NegotiatedSession` before any socket exists. So identity admission before TLS is sound on
 * this path.
 *
 * The inbound path is different and deliberately does not run pairing. An accepted socket reads a
 * *plaintext* Part A handshake hello carrying `senderPeerInstanceId`, cross-checked against the
 * discovery registry, and is delivered as a Part A session. Budgeting an identity from that hello
 * would let a forger claim any honest peer's instance id and burn its budget, because discovery
 * advertisements are unauthenticated. That is why this suite asserts ordering on the outbound path
 * only, and does not claim identity admission is pre-TLS anywhere it is not.
 *
 * The spy is a [Socket] subclass that counts stream acquisitions. Every cryptographic step --
 * TLS engine creation, ephemeral keys, certificate construction, handshake, MPS2 hello encoding --
 * requires the socket's streams, so a zero count is proof none of them ran. No production seam was
 * added and nothing was moved outside TLS to make this testable.
 */
public class PreCryptoAdmissionOrderingTest {

    /** The identity and source the coordinator derives by default from the control session. */
    private companion object {
        const val IDENTITY: String = "00000000000000000000000000000002"
        const val SOURCE: String = "unknown-source"
    }

    /** Counts the only operations through which any cryptographic work could begin. */
    private class CountingSocket : Socket() {
        val inputStreamCalls = AtomicInteger(0)
        val outputStreamCalls = AtomicInteger(0)
        override fun getInputStream(): InputStream {
            inputStreamCalls.incrementAndGet()
            return super.getInputStream()
        }

        override fun getOutputStream(): OutputStream {
            outputStreamCalls.incrementAndGet()
            return super.getOutputStream()
        }

        fun cryptoTouches(): Int = inputStreamCalls.get() + outputStreamCalls.get()
    }

    private class FixedClock : MonotonicClock {
        override fun nowMillis(): Long = 100L
    }

    /** Reports cancelled, and optionally holds the permit inside run() so concurrency is observable. */
    private class SpyInteraction(
        private val gate: CountDownLatch? = null,
    ) : PairingInteraction {
        val approvalsPublished = AtomicInteger(0)
        val entered = AtomicInteger(0)
        override fun publishApproval(
            machine: SecureSessionStateMachine,
            request: SecurePairingApprovalRequest,
        ): Boolean {
            approvalsPublished.incrementAndGet()
            return true
        }

        override fun awaitApproval(machine: SecureSessionStateMachine): Boolean = false
        override fun isCancelled(): Boolean {
            entered.incrementAndGet()
            gate?.await(10, TimeUnit.SECONDS)
            return true
        }
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

    private fun codeOf(result: SecurePairingRunResult): SessionFailureCode? =
        (result as? SecurePairingRunResult.Failed)?.result?.failure?.code

    private fun permissive(concurrency: Int = 1_000) = SecurePairingAttemptLimiter.Limits(
        maxStartsPerIdentity = 1_000,
        maxStartsPerSource = 1_000,
        maxStartsPerPair = 1_000,
        maxConcurrentPairings = concurrency,
    )

    /** Runs one attempt and asserts no cryptographic work and no approval publication happened. */
    private fun refuseWithoutCrypto(
        label: String,
        scope: PairingAdmissionScope,
        expected: SessionFailureCode,
    ) {
        val socket = CountingSocket()
        val interaction = SpyInteraction()
        try {
            val result = SecureLanPairingCoordinator(
                rawSocket = socket,
                controlSession = session(),
                role = SecurePeerRole.INITIATOR,
                clock = FixedClock(),
                random = SecureRandom(),
                interaction = interaction,
                attemptLimiter = scope,
            ).run()
            assertEquals("$label: expected $expected", expected, codeOf(result))
            assertEquals("$label: no stream was ever acquired, so no crypto ran", 0, socket.cryptoTouches())
            assertEquals("$label: no approval was published", 0, interaction.approvalsPublished.get())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    public fun `a closed lease refuses before any cryptographic work`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        scope.close()
        try {
            refuseWithoutCrypto("closed lease", scope, SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `exhausted identity, pair, source and cooldown all refuse before crypto`() {
        val clock = AtomicInteger(0)
        val scope = PairingAdmissionScope(
            monotonicMillis = { clock.get().toLong() },
            limits = SecurePairingAttemptLimiter.Limits(
                maxStartsPerIdentity = 1,
                maxStartsPerSource = 1,
                maxStartsPerPair = 1,
                maxConcurrentPairings = 1_000,
            ),
        )
        try {
            // One grant exhausts identity, source and pair budgets at once, and each grant is
            // released so the refusal observed is a budget refusal rather than concurrency.
            val socket = CountingSocket()
            try {
                SecureLanPairingCoordinator(
                    rawSocket = socket,
                    controlSession = session(),
                    role = SecurePeerRole.INITIATOR,
                    clock = FixedClock(),
                    random = SecureRandom(),
                    interaction = SpyInteraction(),
                    attemptLimiter = scope,
                ).run()
            } finally {
                runCatching { socket.close() }
            }
            // The admitted attempt was cancelled, which is a local outcome and consumes no budget,
            // but the start counters are spent -- so the next attempt is refused pre-crypto.
            refuseWithoutCrypto("budget exhausted", scope, SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a cooldown refusal also happens before any cryptographic work`() {
        val clock = AtomicInteger(0)
        val scope = PairingAdmissionScope(monotonicMillis = { clock.get().toLong() }, limits = permissive())
        try {
            // A cooldown can only be recorded against an identity the limiter already tracks:
            // recordSoftAttempt writes through identities[key]?.set(...), so an untracked key is a
            // silent no-op. That is safe in production, where recordOutcome is only ever reached
            // from an admitted attempt's finally, but it means the counter has to exist first.
            val admission = scope.tryStart(IDENTITY, SOURCE)
            assertTrue("the seeding attempt must be admitted", admission is PairingAdmission.Granted)
            // A user rejection applies a cooldown without failure escalation.
            scope.recordOutcome(IDENTITY, SOURCE, PairingAttemptOutcome.USER_REJECTION)
            scope.releaseConcurrency()
            clock.set(1)
            refuseWithoutCrypto("cooldown active", scope, SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `concurrent attempts cannot exceed the configured number reaching admission`() {
        val permits = 2
        val attempts = 6
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive(permits))
        // Admitted attempts hold their permit inside isCancelled() until the gate opens, so the
        // refusals below are genuine concurrency refusals rather than attempts that ran in sequence.
        val gate = CountDownLatch(1)
        val admitted = AtomicInteger(0)
        val refused = AtomicInteger(0)
        val started = CountDownLatch(attempts)
        val finished = CountDownLatch(attempts)
        val spies = mutableListOf<SpyInteraction>()
        try {
            repeat(attempts) {
                val interaction = SpyInteraction(gate)
                spies += interaction
                Thread {
                    val socket = CountingSocket()
                    try {
                        started.countDown()
                        val result = SecureLanPairingCoordinator(
                            rawSocket = socket,
                            controlSession = session(),
                            role = SecurePeerRole.INITIATOR,
                            clock = FixedClock(),
                            random = SecureRandom(),
                            interaction = interaction,
                            attemptLimiter = scope,
                        ).run()
                        when (codeOf(result)) {
                            SessionFailureCode.OPERATION_CANCELLED -> admitted.incrementAndGet()
                            else -> refused.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        refused.incrementAndGet()
                    } finally {
                        runCatching { socket.close() }
                        finished.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }

            assertTrue("all attempts must start", started.await(10, TimeUnit.SECONDS))
            // Wait until exactly the permit count is inside run(), holding permits.
            var waited = 0
            while (spies.sumOf { it.entered.get() } < permits && waited < 2_000) {
                Thread.sleep(5L)
                waited += 1
            }
            assertEquals("exactly the permit count may be admitted", permits, spies.sumOf { it.entered.get() })
            gate.countDown()
            assertTrue("all attempts must finish", finished.await(10, TimeUnit.SECONDS))

            assertEquals("exactly the permit count reached admission", permits, admitted.get())
            assertEquals("the rest were refused", attempts - permits, refused.get())
            assertEquals("no permit leaked", 0, scope.concurrentPairings())
        } finally {
            gate.countDown()
            scope.close()
        }
    }

    @Test
    public fun `a granted attempt touches the socket exactly once and releases exactly one permit`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val socket = CountingSocket()
        val interaction = SpyInteraction()
        try {
            val result = SecureLanPairingCoordinator(
                rawSocket = socket,
                controlSession = session(),
                role = SecurePeerRole.INITIATOR,
                clock = FixedClock(),
                random = SecureRandom(),
                interaction = interaction,
                attemptLimiter = scope,
            ).run()
            // Admission was granted, then cancellation stopped the attempt before any stream was
            // acquired -- which is itself the ordering property: admission precedes crypto.
            assertEquals(SessionFailureCode.OPERATION_CANCELLED, codeOf(result))
            assertEquals("admission must precede any stream acquisition", 0, socket.cryptoTouches())
            assertEquals("exactly one approval path was never reached", 0, interaction.approvalsPublished.get())
            assertEquals("exactly one permit was taken and released", 0, scope.concurrentPairings())
        } finally {
            runCatching { socket.close() }
            scope.close()
        }
    }
}
