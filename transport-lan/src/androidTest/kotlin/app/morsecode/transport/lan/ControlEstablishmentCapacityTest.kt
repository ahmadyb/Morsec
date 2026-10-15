package app.morsecode.transport.lan

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.ControlConnectionResult
import app.morsecode.core.transfer.session.DiscoveryDiagnostics
import app.morsecode.core.transfer.session.DiscoveryLease
import app.morsecode.core.transfer.session.DiscoveryListener
import app.morsecode.core.transfer.session.DiscoveryPhase
import app.morsecode.core.transfer.session.DiscoveryRequest
import app.morsecode.core.transfer.session.DiscoveryStartResult
import app.morsecode.core.transfer.session.DiscoveredPeer
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairableControlSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePairingListener
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.transport.lan.security.ControlEstablishmentObserver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Control-establishment capacity, shutdown and observer safety on the production owner.
 *
 * One lease, bound once. The provider binds the fixed control port with `reuseAddress = false`, so
 * every closed connection leaves that port in TIME_WAIT and a second lease in the same process
 * cannot bind it. Every scenario here therefore runs against a single lease in a single method, and
 * the second-start guard is asserted rather than a second lease being opened.
 *
 * A peer that connects and says nothing is the blocker: the accept loop takes the permit and submits
 * the task before any byte is read, and the task's first act is `readHandshake`, which blocks. So a
 * silent peer parks a real worker after real permit acquisition and before any handshake work, with
 * no cryptographic involvement.
 *
 * PHASE MARKERS go to logcat under [PHASE_TAG] and nowhere else. They carry a fixed phase name and
 * bounded counters only -- no peer identifier, session identifier, address, certificate, proof, key,
 * raw exception or socket detail. The instrumentation wrapper captures that one tag and the
 * diagnostic step reads it back, which is the only evidence available when a run hangs.
 */
@RunWith(AndroidJUnit4::class)
public class ControlEstablishmentCapacityTest {

    /**
     * Counts the four capacity events and releases a latch on a chosen threshold.
     *
     * Acquisitions and releases are counted separately: a leak shows as acquisitions exceeding
     * releases, a double release as the reverse. One combined counter could not tell them apart.
     *
     * The defect switches exist to prove production isolates a misbehaving observer. Note what they
     * cannot prove: production swallows a thrown RuntimeException, but it calls the observer inline
     * on the accept thread and on control workers, so an observer that blocked *forever* would stall
     * them. The delay used here is finite for exactly that reason.
     */
    private class CountingObserver : ControlEstablishmentObserver {
        val submitted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)

        @Volatile
        var throwOnEveryEvent: Boolean = false

        @Volatile
        var delayMillis: Long = 0L

        private val acquisitionThresholds = mutableMapOf<Int, CountDownLatch>()
        private val releaseThresholds = mutableMapOf<Int, CountDownLatch>()

        @Synchronized
        fun latchAtAcquisitions(count: Int): CountDownLatch =
            acquisitionThresholds.getOrPut(count) { CountDownLatch(1) }

        @Synchronized
        fun latchAtReleases(count: Int): CountDownLatch =
            releaseThresholds.getOrPut(count) { CountDownLatch(1) }

        private fun misbehave(event: String) {
            val delay = delayMillis
            if (delay > 0L) Thread.sleep(delay)
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on $event")
        }

        // Counting happens BEFORE the defect is raised, deliberately. A test that could not see its
        // own permits while the observer was throwing would have no way to know four were held
        // before it tried the fifth, and would then be racing the accept loop rather than asserting
        // anything. Counting first also makes this the stronger test: production must isolate the
        // throw without losing the observation.

        override fun onControlTaskSubmitted() {
            submitted.incrementAndGet()
            misbehave("submission")
        }

        override fun onControlTaskRejected() {
            rejected.incrementAndGet()
            misbehave("rejection")
        }

        override fun onControlPermitAcquired() {
            val total = acquired.incrementAndGet()
            synchronized(this) { acquisitionThresholds[total] }?.countDown()
            misbehave("acquisition")
        }

        override fun onControlPermitReleased() {
            val total = released.incrementAndGet()
            synchronized(this) { releaseThresholds[total] }?.countDown()
            misbehave("release")
        }
    }

    private class RecordingListener : DiscoveryListener {
        val delivered = AtomicInteger(0)

        override fun onDiscoveryChanged(
            phase: DiscoveryPhase,
            peers: List<DiscoveredPeer>,
            diagnostics: DiscoveryDiagnostics,
        ) = Unit

        override fun onIncomingControlSession(result: ControlConnectionResult.Connected) {
            delivered.incrementAndGet()
        }
    }

    /** Sockets proven to have received handshake bytes; see [productionWrote]. */
    private val wroteSockets: MutableSet<Socket> =
        java.util.Collections.synchronizedSet(HashSet<Socket>())

    /** Emits a bounded phase marker: a fixed name plus counters, and nothing identifying. */
    private fun mark(phase: String, detail: String = "") {
        Log.i(PHASE_TAG, if (detail.isEmpty()) phase else "$phase $detail")
    }

    private fun <T> phase(name: String, body: () -> T): T = try {
        body()
    } catch (thrown: Throwable) {
        val frames = thrown.stackTrace.take(FRAME_LIMIT).joinToString("\n  ") { "at $it" }
        val cause = thrown.cause?.let { "\ncaused by: $it" } ?: ""
        mark("phase_failed", "phase=$name")
        fail("$name threw $thrown$cause\n  $frames")
        throw thrown
    }

    private fun controlEndpoint(): SocketAddress =
        InetSocketAddress(InetAddress.getLoopbackAddress(), NetworkPorts.PEER_CONTROL)

    private fun connectPeer(): Socket = Socket().apply {
        soTimeout = PEER_READ_TIMEOUT_MILLIS
        connect(controlEndpoint(), CONNECT_TIMEOUT_MILLIS)
    }

    /** True once production has closed its end, which is how a refused peer is detected. */
    private fun peerEndIsClosed(socket: Socket): Boolean = try {
        socket.getInputStream().read() == -1
    } catch (_: java.io.IOException) {
        true
    }

    /** Polls a condition against a deadline instead of sleeping for a guessed duration. */
    private fun awaitTrue(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun establishmentCapacityShutdownAndObserverDefectsOnOneBoundLease() {
        mark("test_started", "scenario=establishment_capacity")
        val observer = CountingObserver()
        val listener = RecordingListener()
        val peers = mutableListOf<Socket>()
        var lease: DiscoveryLease? = null
        try {
            val application = phase("obtaining the instrumentation application") {
                ApplicationProvider.getApplicationContext<Application>()
            }
            val provider = phase("constructing the provider") {
                LanPeerDiscoveryProvider(context = application)
            }
            phase("installing the observation seam") {
                provider.establishmentObserver = observer
            }
            // Both threshold latches are registered before the lease starts. A latch installed after
            // the events it waits for would miss them and wait for something already past.
            val fourAcquired = phase("installing the acquisition latch") {
                observer.latchAtAcquisitions(ACTIVE_LIMIT)
            }
            val fourReleased = phase("installing the release latch") {
                observer.latchAtReleases(ACTIVE_LIMIT)
            }

            val result = phase("starting the discovery lease") {
                provider.start(
                    DiscoveryRequest(displayName = "Capacity", appVersion = "1.0.0"),
                    listener,
                )
            }
            assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
            // An immutable local, not the captured var: Kotlin refuses to smart-cast a var that a
            // closure mutates, so the close below would not compile against `lease` directly.
            val activeLease = (result as DiscoveryStartResult.Started).lease
            lease = activeLease
            mark("lease_started", "active=0 held=0")

            // --- four establishments, each holding one real permit -------------------------------
            repeat(ACTIVE_LIMIT) { index ->
                peers += phase("connecting peer-${index + 1}") {
                    connectPeer().also {
                        assertTrue("the fixed control port did not accept peer-${index + 1}", it.isConnected)
                    }
                }
            }
            assertTrue(
                "only ${observer.acquired.get()} of $ACTIVE_LIMIT control permits were acquired",
                fourAcquired.await(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            // Acquisition and submission are two separate observer notifications. The latch above
            // counts acquisitions, so it can release before the fourth submission is observed, and
            // run 38705203819 read 3 submissions here with all four permits already held. Bound the
            // submission count on its own terms rather than inferring it from the acquisition latch.
            assertTrue(
                "the ${observer.submitted.get()} observed submissions never reached $ACTIVE_LIMIT",
                awaitTrue(SUBMISSION_TIMEOUT_SECONDS) { observer.submitted.get() >= ACTIVE_LIMIT },
            )
            assertEquals("four peers produced ${observer.submitted.get()} submissions, " +
                "expected $ACTIVE_LIMIT", ACTIVE_LIMIT, observer.submitted.get())
            mark("four_permits_acquired",
                "active=${observer.acquired.get()} held=${observer.acquired.get()} " +
                    "submissions=${observer.submitted.get()} open_sockets=${peers.size}")

            // --- the fifth is a capacity refusal, not a queue rejection --------------------------
            val fifth = phase("connecting peer-5") { connectPeer() }
            peers += fifth
            assertTrue("peer-5 was not closed by production, so it reached the executor",
                awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(fifth) })
            assertEquals("a fifth control permit was acquired; capacity exceeded its bound",
                ACTIVE_LIMIT, observer.acquired.get())
            assertEquals("a fifth task was submitted to the control executor",
                ACTIVE_LIMIT, observer.submitted.get())
            assertEquals("the refused peer must not be reported as a queue rejection",
                0, observer.rejected.get())
            assertEquals("a silent peer must not be delivered as a control session",
                0, listener.delivered.get())
            mark("fifth_attempt_refused", "result=capacity_refusal active=$ACTIVE_LIMIT " +
                "submissions=${observer.submitted.get()} rejections=${observer.rejected.get()}")

            // --- a second start while the lease is live is refused, not a second bound lease -----
            val secondStart = phase("starting a second lease while the first is live") {
                provider.start(
                    DiscoveryRequest(displayName = "Capacity", appVersion = "1.0.0"),
                    listener,
                )
            }
            assertTrue("a second concurrent lease was allowed: $secondStart",
                secondStart is DiscoveryStartResult.Failed)
            assertEquals("the second start was refused for the wrong reason",
                SessionFailureCode.DISCOVERY_ALREADY_ACTIVE,
                (secondStart as DiscoveryStartResult.Failed).failure.code)
            mark("second_start_refused", "result=discovery_already_active")

            // --- release every blocker; each permit returns exactly once -------------------------
            peers.forEach { runCatching { it.close() } }
            peers.clear()
            mark("blockers_released", "open_sockets=0")
            assertTrue(
                "closing every blocker returned ${observer.released.get()} permits, " +
                    "expected $ACTIVE_LIMIT",
                fourReleased.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertEquals("permits did not settle at zero",
                observer.acquired.get(), observer.released.get())
            mark("four_permits_released",
                "acquired=${observer.acquired.get()} released=${observer.released.get()}")

            // --- the shared executor's queue, reached the way production can reach it ------------
            runMixedSaturationMatrix(provider, activeLease, observer, listener)

            // --- the tighter security bound, pinned independently of executor capacity -----------
            runPairingConcurrencyCap(provider, activeLease, observer)

            // --- a misbehaving observer must not change scheduling or block cleanup --------------
            observer.throwOnEveryEvent = true
            observer.delayMillis = OBSERVER_DELAY_MILLIS
            val eightAcquired = phase("installing the second acquisition latch") {
                // Relative, not a fixed total: the mixed-saturation and concurrency-cap phases
                // above consume acquisitions first, and a threshold registered below the
                // count already reached would never count down.
                observer.latchAtAcquisitions(observer.acquired.get() + ACTIVE_LIMIT)
            }
            val defectAcquisitionTarget = observer.acquired.get() + ACTIVE_LIMIT
            val defectPeers = mutableListOf<Socket>()
            try {
                repeat(ACTIVE_LIMIT) { index ->
                    defectPeers += phase("connecting peer-${index + 1} with a defective observer") {
                        connectPeer()
                    }
                }
                assertTrue(
                    "the defective observer stopped permits being acquired; reached " +
                        "${observer.acquired.get()}, expected $defectAcquisitionTarget",
                    eightAcquired.await(DEFECT_PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val refused = phase("connecting peer-5 with a defective observer") { connectPeer() }
                defectPeers += refused
                assertTrue("the fifth peer was not closed by production while the observer was " +
                    "throwing and delaying",
                    awaitTrue(DEFECT_REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(refused) })
                assertEquals("a silent peer was delivered as a session while the observer was " +
                    "defective", 0, listener.delivered.get())
                mark("defective_observer_tolerated", "refused=true delivered=0")
            } finally {
                defectPeers.forEach { runCatching { it.close() } }
                observer.throwOnEveryEvent = false
                observer.delayMillis = 0L
            }

            // --- stop, and stop again ------------------------------------------------------------
            phase("closing the lease") { activeLease.close() }
            lease = null
            phase("closing the lease a second time") { activeLease.close() }
            mark("lease_stopped", "repeated_stop=true")
            assertEquals("a session was delivered after the lease stopped",
                0, listener.delivered.get())
        } finally {
            lease?.let { runCatching { it.close() } }
            peers.forEach { runCatching { it.close() } }
            peers.clear()

            // Proving the fixed port has no listener is part of proving cleanup completed, so it
            // belongs here rather than after it. The terminal marker has to be the last thing this
            // test emits, because CI fails a green run that does not end on it -- run 38595962958
            // passed all five tests and still failed this check for emitting port_released
            // afterwards. awaitTrue returns a Boolean and never throws, so it is safe in finally.
            val portFree = awaitTrue(PORT_FREE_TIMEOUT_SECONDS) {
                runCatching {
                    Socket().use { it.connect(controlEndpoint(), CONNECT_TIMEOUT_MILLIS) }
                }.isFailure
            }
            mark("cleanup_completed",
                "port_free=$portFree acquired=${observer.acquired.get()} " +
                    "released=${observer.released.get()} " +
                    "submissions=${observer.submitted.get()} rejections=${observer.rejected.get()} " +
                    "delivered=${listener.delivered.get()}")
            assertTrue("the lease stopped but the fixed control port is still accepting connections",
                portFree)
        }
    }

    /**
     * True once production has written to, or closed, the fixture's far end.
     *
     * Both count as "a task ran". A queued task has done neither: it cannot reach the socket at
     * all, so its far end stays silent and open. That difference is what separates active from
     * queued here, and it is observable from production doing real work rather than from reading a
     * private field. The attempt limiter closes the socket before any TLS byte is written when it
     * refuses an attempt, so a refusal looks like a close rather than a write, and this treats both
     * as "reached the socket" on purpose.
     *
     * soTimeout is what keeps this bounded: a timeout throws, is not an EOF, and reads as untouched.
     */
    private fun productionTouched(socket: Socket): Boolean =
        // available() lives on the stream, not on Socket -- a first attempt called it on the
        // socket and failed to compile for exactly that reason.
        socket.getInputStream().available() > 0 ||
            runCatching { socket.getInputStream().read() }.getOrNull() == -1

    /**
     * True only if production actually wrote bytes to this far end, which is what proves a pairing
     * task started its handshake.
     *
     * Deliberately not the same as [productionTouched]. Production closes a session's socket when a
     * pairing is rejected or refused -- `finish()` closes on any Failed result -- so a closed far
     * end is normal cleanup and says nothing about whether cryptographic work happened. Run
     * 38656187015 measured exactly that: the fifth pairing was correctly rejected with
     * OPERATION_QUEUE_FULL and then correctly closed, and an assertion keyed on "touched" read the
     * cleanup as late execution.
     *
     * Results are memoised per socket: a probe read consumes a byte, and a later probe must not
     * then report that nothing was ever written.
     */
    private fun productionWrote(socket: Socket): Boolean {
        if (wroteSockets.contains(socket)) return true
        val wrote = socket.getInputStream().available() > 0 ||
            (runCatching { socket.getInputStream().read() }.getOrNull() ?: -1) >= 0
        if (wrote) wroteSockets.add(socket)
        return wrote
    }

    /** True once a pairing operation has been admitted by the limiter and is doing real work. */
    private fun admittedAndRunning(outcome: PairingOutcome, farEnd: Socket): Boolean =
        outcome.result == null && productionWrote(farEnd)

    /** The refusal code a pairing operation ended with, or null if it has not ended. */
    private fun refusalCode(outcome: PairingOutcome): SessionFailureCode? =
        (outcome.result as? SecurePairingResult.Failed)?.failure?.code

    /**
     * Mixed saturation, which is how production can actually fill the shared executor's queue.
     *
     * Four silent inbound establishments take all four permits and park all four workers inside
     * `readHandshake`. Four pairing operations then enter the queue and cannot run, and a fifth is
     * rejected by AbortPolicy with OPERATION_QUEUE_FULL.
     *
     * WHY NOT PAIRING WORK ALONE. A first version of this submitted nine pairings and expected four
     * active and four queued. Run 38634422755 measured why that is impossible: the lease-scoped
     * attempt limiter admits at most `maxConcurrentPairings` (2) concurrent pairings and refuses the
     * rest before any socket work, so refused tasks finish in microseconds and free their workers.
     * Pairing work alone cannot hold four workers, so it cannot keep the queue full. Establishment
     * work can, because it never goes through the pairing limiter. The layered design is therefore
     * establishment capacity 4, executor 4 workers plus 4 queued, pairing security concurrency 2.
     *
     * Queue emptiness is proved rather than read: there is no visible queue size, but if four
     * pairing tasks are accepted and the fifth is rejected, the queue held exactly four free slots.
     */
    private fun runMixedSaturationMatrix(
        provider: LanPeerDiscoveryProvider,
        lease: DiscoveryLease,
        observer: CountingObserver,
        listener: RecordingListener,
    ) {
        val acquiredBefore = observer.acquired.get()
        val releasedBefore = observer.released.get()
        val submittedBefore = observer.submitted.get()
        val rejectedBefore = observer.rejected.get()
        val deliveredBefore = listener.delivered.get()
        val fourHeld = observer.latchAtAcquisitions(acquiredBefore + ACTIVE_LIMIT)
        val pairingTotal = QUEUED_PAIRINGS + 1
        mark("mixed_matrix_started", "establishments=$ACTIVE_LIMIT pairings=$pairingTotal")

        ServerSocket(0).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val establishments = ArrayList<Socket>(ACTIVE_LIMIT)
            val sessions = ArrayList<PairableControlSession>(pairingTotal)
            val farEnds = ArrayList<Socket>(pairingTotal)
            val outcomes = ArrayList<PairingOutcome>(pairingTotal)
            try {
                // --- occupy every worker with a real establishment -------------------------------
                repeat(ACTIVE_LIMIT) { index ->
                    establishments += phase("connecting blocking establishment ${index + 1}") {
                        connectPeer()
                    }
                }
                assertTrue(
                    "only ${observer.acquired.get() - acquiredBefore} of $ACTIVE_LIMIT " +
                        "establishment permits were acquired",
                    fourHeld.await(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                // Same race as the single-lease case: fourHeld bounds acquisitions, not submissions.
                assertTrue(
                    "the ${observer.submitted.get() - submittedBefore} observed submissions never " +
                        "reached $ACTIVE_LIMIT",
                    awaitTrue(SUBMISSION_TIMEOUT_SECONDS) {
                        observer.submitted.get() - submittedBefore >= ACTIVE_LIMIT
                    },
                )
                assertEquals(
                    "four establishments produced " +
                        "${observer.submitted.get() - submittedBefore} submissions, expected " +
                        "$ACTIVE_LIMIT",
                    ACTIVE_LIMIT,
                    observer.submitted.get() - submittedBefore,
                )
                assertEquals("a blocked establishment was delivered as a control session",
                    deliveredBefore, listener.delivered.get())
                mark("establishments_blocking",
                    "active=$ACTIVE_LIMIT permits_held=$ACTIVE_LIMIT delivered=0")

                // --- fill the pending queue with pairing work ------------------------------------
                repeat(pairingTotal) { index ->
                    val near = Socket()
                    near.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    val far = phase("accepting fixture socket ${index + 1}") { server.accept() }
                    far.soTimeout = PROBE_READ_TIMEOUT_MILLIS
                    farEnds += far
                    outcomes += PairingOutcome()
                    sessions += phase("constructing control session ${index + 1}") {
                        provider.createControlSessionForPairingCapacityTest(
                            lease = lease,
                            socket = near,
                            negotiated = pairingFixture(index),
                            role = SecurePeerRole.INITIATOR,
                        )
                    }
                }
                repeat(QUEUED_PAIRINGS) { index ->
                    phase("beginning queued pairing ${index + 1}") {
                        sessions[index].beginSecurePairing(outcomes[index])
                    }
                }
                assertTrue(
                    "only ${observer.submitted.get() - submittedBefore - ACTIVE_LIMIT} pairing " +
                        "tasks were submitted, expected $QUEUED_PAIRINGS",
                    awaitTrue(SUBMISSION_TIMEOUT_SECONDS) {
                        observer.submitted.get() - submittedBefore - ACTIVE_LIMIT == QUEUED_PAIRINGS
                    },
                )
                // A queued task has not run at all: no socket activity, and no terminal result, so
                // it has not even reached the attempt limiter.
                assertEquals("a queued pairing reached its socket while every worker was blocked",
                    0, farEnds.take(QUEUED_PAIRINGS).count { productionTouched(it) })
                assertEquals("a queued pairing reached a terminal result, so it ran and met the limiter",
                    0, outcomes.take(QUEUED_PAIRINGS).count { it.result != null })
                assertEquals(
                    "the pairing path acquired " +
                        "${observer.acquired.get() - acquiredBefore - ACTIVE_LIMIT} establishment " +
                        "permits; pairing and establishment are separate limits",
                    0,
                    observer.acquired.get() - acquiredBefore - ACTIVE_LIMIT,
                )
                mark("pairing_queue_filled",
                    "queued=$QUEUED_PAIRINGS started=0 limiter_hits=0 permits_held=$ACTIVE_LIMIT")

                // --- the fifth pairing is rejected by AbortPolicy ---------------------------------
                phase("beginning the fifth pairing with the queue full") {
                    sessions[QUEUED_PAIRINGS].beginSecurePairing(outcomes[QUEUED_PAIRINGS])
                }
                assertTrue(
                    "the fifth pairing never reached a terminal result; observed " +
                        "${outcomes[QUEUED_PAIRINGS].result}",
                    outcomes[QUEUED_PAIRINGS].awaitTerminal(QUEUE_REFUSAL_TIMEOUT_SECONDS),
                )
                assertEquals(
                    "the fifth pairing was refused with ${refusalCode(outcomes[QUEUED_PAIRINGS])} " +
                        "rather than a queue-full refusal",
                    SessionFailureCode.OPERATION_QUEUE_FULL,
                    refusalCode(outcomes[QUEUED_PAIRINGS]),
                )
                assertEquals(
                    "the production executor rejected " +
                        "${observer.rejected.get() - rejectedBefore} tasks, expected exactly the " +
                        "fifth pairing",
                    1,
                    observer.rejected.get() - rejectedBefore,
                )
                assertTrue(
                    "the rejected pairing wrote handshake bytes, so it ran after rejection; a " +
                        "closed socket here is the rejection's own cleanup, not late execution",
                    !productionWrote(farEnds[QUEUED_PAIRINGS]),
                )
                assertEquals("the rejected pairing published an approval",
                    0, outcomes[QUEUED_PAIRINGS].approvals.get())
                mark("fifth_pairing_refused",
                    "code=operation_queue_full rejected=${observer.rejected.get()}")

                // --- drain one worker at a time; the limiter admits at most two -------------------
                phase("releasing the first establishment worker") {
                    runCatching { establishments[0].close() }
                }
                assertTrue("no queued pairing began after the first worker was released",
                    awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) {
                        (0 until QUEUED_PAIRINGS).count {
                            admittedAndRunning(outcomes[it], farEnds[it])
                        } >= 1
                    })
                phase("releasing the second establishment worker") {
                    runCatching { establishments[1].close() }
                }
                assertTrue("a second queued pairing did not begin after the second worker was released",
                    awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) {
                        (0 until QUEUED_PAIRINGS).count {
                            admittedAndRunning(outcomes[it], farEnds[it])
                        } >= MAX_CONCURRENT_PAIRINGS
                    })
                // Taken now, while both are still inside the handshake. Waiting for the refusals
                // first could let the admitted pairings hit their own handshake deadline.
                assertEquals(
                    "more than $MAX_CONCURRENT_PAIRINGS queued pairings reached cryptographic work",
                    MAX_CONCURRENT_PAIRINGS,
                    (0 until QUEUED_PAIRINGS).count { admittedAndRunning(outcomes[it], farEnds[it]) },
                )

                phase("releasing the remaining establishment workers") {
                    establishments.drop(MAX_CONCURRENT_PAIRINGS).forEach { runCatching { it.close() } }
                }
                assertTrue(
                    "the pairings beyond the concurrency cap did not report a limit refusal; " +
                        "observed ${outcomes.take(QUEUED_PAIRINGS).map { it.result }}",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) {
                        outcomes.take(QUEUED_PAIRINGS).count {
                            refusalCode(it) == SessionFailureCode.SECURE_SESSION_LIMIT_REACHED
                        } == QUEUED_PAIRINGS - MAX_CONCURRENT_PAIRINGS
                    },
                )
                // "No cryptographic work" for a limiter refusal is proved by the refusal code
                // itself, not by socket activity. The limiter is consulted before
                // ConscryptSecureSessionEngine is constructed, and its refusal path closes the raw
                // socket -- so a refused pairing legitimately shows a closed far end, and socket
                // state cannot distinguish it from one that did handshake work. What separates them
                // is that an admitted pairing is still running while a refused one has terminated.
                assertEquals(
                    "a pairing beyond the concurrency cap was still running cryptographic work",
                    0,
                    (0 until QUEUED_PAIRINGS).count {
                        refusalCode(outcomes[it]) ==
                            SessionFailureCode.SECURE_SESSION_LIMIT_REACHED &&
                            outcomes[it].result == null
                    },
                )
                mark("pairing_queue_drained",
                    "admitted=$MAX_CONCURRENT_PAIRINGS " +
                        "limit_refusals=${QUEUED_PAIRINGS - MAX_CONCURRENT_PAIRINGS}")

                // --- settle ----------------------------------------------------------------------
                phase("closing every fixture session") {
                    sessions.forEach { runCatching { it.close() } }
                }
                assertTrue("a pairing operation never reached a terminal result",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) { outcomes.all { it.result != null } })
                assertEquals("a fixture pairing authenticated against a silent peer",
                    0, outcomes.count { it.result is SecurePairingResult.Authenticated })
                phase("closing every blocking establishment") {
                    establishments.forEach { runCatching { it.close() } }
                }
                assertTrue(
                    "establishment permits did not settle; acquired " +
                        "${observer.acquired.get() - acquiredBefore}, released " +
                        "${observer.released.get() - releasedBefore}",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) {
                        observer.released.get() - releasedBefore ==
                            observer.acquired.get() - acquiredBefore
                    },
                )
                assertEquals("a session was delivered during the mixed saturation matrix",
                    deliveredBefore, listener.delivered.get())
                mark("mixed_matrix_completed",
                    "acquired=${observer.acquired.get() - acquiredBefore} " +
                        "released=${observer.released.get() - releasedBefore} delivered=0")

                // --- honest recovery --------------------------------------------------------------
                runHonestRecovery(observer, listener, deliveredBefore)
            } finally {
                sessions.forEach { runCatching { it.close() } }
                farEnds.forEach { runCatching { it.close() } }
                establishments.forEach { runCatching { it.close() } }
            }
        }
    }

    /** One ordinary establishment, admitted and accepted again after everything has drained. */
    private fun runHonestRecovery(
        observer: CountingObserver,
        listener: RecordingListener,
        deliveredBefore: Int,
    ) {
        val acquiredBefore = observer.acquired.get()
        val submittedBefore = observer.submitted.get()
        val rejectedBefore = observer.rejected.get()
        val honest = phase("starting an honest establishment after recovery") {
            connectPeer().also { it.soTimeout = PROBE_READ_TIMEOUT_MILLIS }
        }
        try {
            assertTrue("the honest establishment acquired no permit after recovery",
                awaitTrue(PERMIT_TIMEOUT_SECONDS) { observer.acquired.get() > acquiredBefore })
            assertTrue("the honest establishment was never submitted to the executor",
                awaitTrue(SUBMISSION_TIMEOUT_SECONDS) { observer.submitted.get() > submittedBefore })
            assertEquals("the honest establishment was rejected after recovery",
                0, observer.rejected.get() - rejectedBefore)
            // A read that times out proves the peer is still connected and production is waiting on
            // it, which is the controlled blocking phase. peerEndIsClosed cannot be used here: it
            // reports a timeout as "closed", because it was written to wait for a refusal rather
            // than to prove a connection is alive.
            val stillWaiting = runCatching { honest.getInputStream().read() }
                .exceptionOrNull() is java.net.SocketTimeoutException
            assertTrue("the honest establishment was closed instead of reaching its blocking phase",
                stillWaiting)
            assertEquals("the honest establishment was delivered without completing a handshake",
                deliveredBefore, listener.delivered.get())
            mark("honest_establishment_recovered",
                "acquired=1 submitted=1 rejected=0 delivered=0")
        } finally {
            runCatching { honest.close() }
        }
    }

    /**
     * The tighter bound, pinned independently of executor capacity.
     *
     * On an idle executor every submitted pairing task runs at once, so what stops more than two
     * from doing cryptographic work is the lease-scoped attempt limiter, not the pool. This is the
     * security-relevant limit and it is asserted on its own so that a future change to executor
     * sizing cannot quietly move it.
     */
    private fun runPairingConcurrencyCap(
        provider: LanPeerDiscoveryProvider,
        lease: DiscoveryLease,
        observer: CountingObserver,
    ) {
        val acquiredBefore = observer.acquired.get()
        val total = MAX_CONCURRENT_PAIRINGS + 2
        mark("pairing_cap_started", "pairings=$total cap=$MAX_CONCURRENT_PAIRINGS")

        ServerSocket(0).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val sessions = ArrayList<PairableControlSession>(total)
            val farEnds = ArrayList<Socket>(total)
            val outcomes = ArrayList<PairingOutcome>(total)
            try {
                repeat(total) { index ->
                    val near = Socket()
                    near.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    val far = phase("accepting cap fixture socket ${index + 1}") { server.accept() }
                    far.soTimeout = PROBE_READ_TIMEOUT_MILLIS
                    farEnds += far
                    outcomes += PairingOutcome()
                    sessions += phase("constructing cap control session ${index + 1}") {
                        provider.createControlSessionForPairingCapacityTest(
                            lease = lease,
                            socket = near,
                            negotiated = pairingFixture(CAP_FIXTURE_OFFSET + index),
                            role = SecurePeerRole.INITIATOR,
                        )
                    }
                }
                sessions.forEachIndexed { index, session ->
                    phase("beginning cap pairing ${index + 1}") {
                        session.beginSecurePairing(outcomes[index])
                    }
                }

                assertTrue(
                    "fewer than $MAX_CONCURRENT_PAIRINGS pairings reached cryptographic work on an " +
                        "idle executor",
                    awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) {
                        (0 until total).count { admittedAndRunning(outcomes[it], farEnds[it]) } >=
                            MAX_CONCURRENT_PAIRINGS
                    },
                )
                // Counts, not indices: the pool does not guarantee which task runs first.
                assertEquals(
                    "more than $MAX_CONCURRENT_PAIRINGS pairings were doing cryptographic work",
                    MAX_CONCURRENT_PAIRINGS,
                    (0 until total).count { admittedAndRunning(outcomes[it], farEnds[it]) },
                )
                assertTrue(
                    "the pairings beyond the cap did not report a concurrency limit refusal; " +
                        "observed ${outcomes.map { it.result }}",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) {
                        outcomes.count {
                            refusalCode(it) == SessionFailureCode.SECURE_SESSION_LIMIT_REACHED
                        } == total - MAX_CONCURRENT_PAIRINGS
                    },
                )
                assertEquals("the pairing path acquired establishment permits",
                    0, observer.acquired.get() - acquiredBefore)
                mark("pairing_cap_enforced",
                    "admitted=$MAX_CONCURRENT_PAIRINGS " +
                        "limit_refusals=${total - MAX_CONCURRENT_PAIRINGS} permits_held=0")

                phase("closing every cap fixture session") {
                    sessions.forEach { runCatching { it.close() } }
                }
                assertTrue("a cap pairing never reached a terminal result",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) { outcomes.all { it.result != null } })
                assertEquals("a cap pairing authenticated against a silent peer",
                    0, outcomes.count { it.result is SecurePairingResult.Authenticated })

                // Closing the admitted pairings must give the capacity back.
                val restoredSessions = ArrayList<PairableControlSession>(MAX_CONCURRENT_PAIRINGS)
                val restoredFarEnds = ArrayList<Socket>(MAX_CONCURRENT_PAIRINGS)
                val restoredOutcomes = ArrayList<PairingOutcome>(MAX_CONCURRENT_PAIRINGS)
                try {
                    repeat(MAX_CONCURRENT_PAIRINGS) { index ->
                        val near = Socket()
                        near.connect(
                            InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                            CONNECT_TIMEOUT_MILLIS,
                        )
                        val far = phase("accepting restore socket ${index + 1}") { server.accept() }
                        far.soTimeout = PROBE_READ_TIMEOUT_MILLIS
                        restoredFarEnds += far
                        restoredOutcomes += PairingOutcome()
                        restoredSessions += phase("constructing restore session ${index + 1}") {
                            provider.createControlSessionForPairingCapacityTest(
                                lease = lease,
                                socket = near,
                                negotiated = pairingFixture(CAP_FIXTURE_OFFSET + 32 + index),
                                role = SecurePeerRole.INITIATOR,
                            )
                        }
                    }
                    restoredSessions.forEachIndexed { index, session ->
                        phase("beginning restore pairing ${index + 1}") {
                            session.beginSecurePairing(restoredOutcomes[index])
                        }
                    }
                    assertTrue(
                        "pairing capacity was not restored after the admitted pairings closed; " +
                            "observed ${restoredOutcomes.map { it.result }}",
                        awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) {
                            (0 until MAX_CONCURRENT_PAIRINGS).count {
                                admittedAndRunning(restoredOutcomes[it], restoredFarEnds[it])
                            } == MAX_CONCURRENT_PAIRINGS
                        },
                    )
                    mark("pairing_capacity_restored", "admitted=$MAX_CONCURRENT_PAIRINGS")
                } finally {
                    restoredSessions.forEach { runCatching { it.close() } }
                    restoredFarEnds.forEach { runCatching { it.close() } }
                }
            } finally {
                sessions.forEach { runCatching { it.close() } }
                farEnds.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Receives the production terminal result for one fixture session. */
    private class PairingOutcome : SecurePairingListener {
        private val terminal = CountDownLatch(1)
        val approvals = AtomicInteger(0)

        @Volatile
        var result: SecurePairingResult? = null
            private set

        override fun onApprovalRequired(request: SecurePairingApprovalRequest) {
            // A silent fixture peer can never reach an approval, and production has no
            // auto-approval implementation to reach for either. Counted so a rejected operation
            // can be shown never to have published one.
            approvals.incrementAndGet()
        }

        override fun onCompleted(result: SecurePairingResult) {
            this.result = result
            terminal.countDown()
        }

        fun awaitTerminal(timeoutSeconds: Long): Boolean =
            terminal.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    /** An unauthenticated control session fixture; grants nothing and proves nothing by itself. */
    private fun pairingFixture(index: Int): NegotiatedSession = NegotiatedSession(
        attemptId = SessionId("attempt-${index + 1}"),
        localProfile = pairingProfile(index + 1, "Local"),
        remoteProfile = pairingProfile(index + 512, "Remote"),
        protocolVersion = 1,
        capabilities = NegotiatedCapabilities(
            features = setOf(SessionFeature.CONTROL_HANDSHAKE),
            maxChunkSizeBytes = 4_096,
            resumeSupported = false,
            encryption = EncryptionCapability.NONE,
        ),
    )

    private fun pairingProfile(seed: Int, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId("%032x".format(seed)),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private companion object {
        const val PHASE_TAG = "MorsecA3Phase"
        const val ACTIVE_LIMIT = 4
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val PEER_READ_TIMEOUT_MILLIS = 4_000
        const val PERMIT_TIMEOUT_SECONDS = 4L
        const val REFUSAL_TIMEOUT_SECONDS = 4L
        const val DEFECT_PERMIT_TIMEOUT_SECONDS = 8L
        const val DEFECT_REFUSAL_TIMEOUT_SECONDS = 10L
        const val RELEASE_TIMEOUT_SECONDS = 12L
        const val PORT_FREE_TIMEOUT_SECONDS = 15L
        const val POLL_INTERVAL_MILLIS = 10L
        const val FRAME_LIMIT = 12
        const val TEST_TIMEOUT_MILLIS = 120_000L
        const val OBSERVER_DELAY_MILLIS = 50L
        const val PROBE_READ_TIMEOUT_MILLIS = 200
        const val QUEUED_PAIRINGS = 4
        /** Mirrors SecurePairingAttemptLimiter.Limits.maxConcurrentPairings. Pinned by the verifier. */
        const val MAX_CONCURRENT_PAIRINGS = 2
        const val CAP_FIXTURE_OFFSET = 64
        const val ACCEPT_TIMEOUT_MILLIS = 10_000
        const val SUBMISSION_TIMEOUT_SECONDS = 8L
        const val QUEUE_REFUSAL_TIMEOUT_SECONDS = 8L
        const val ACTIVE_WORK_TIMEOUT_SECONDS = 6L
    }
}
