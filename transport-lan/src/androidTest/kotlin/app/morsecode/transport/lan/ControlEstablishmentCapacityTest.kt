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

            // --- the pairing executor's own queue, and an establishment attempt while it is full -
            runPairingQueueMatrix(provider, activeLease, observer, listener)

            // --- a misbehaving observer must not change scheduling or block cleanup --------------
            observer.throwOnEveryEvent = true
            observer.delayMillis = OBSERVER_DELAY_MILLIS
            val eightAcquired = phase("installing the second acquisition latch") {
                observer.latchAtAcquisitions(ACTIVE_LIMIT * 2)
            }
            val defectPeers = mutableListOf<Socket>()
            try {
                repeat(ACTIVE_LIMIT) { index ->
                    defectPeers += phase("connecting peer-${index + 1} with a defective observer") {
                        connectPeer()
                    }
                }
                assertTrue(
                    "only ${observer.acquired.get()} permits were acquired while the observer was " +
                        "defective, expected ${ACTIVE_LIMIT * 2}",
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
     * Saturates the production pairing executor with nine real sessions, then attempts an
     * establishment while its queue is still full.
     *
     * The sessions come from the internal test factory rather than from injected registry entries,
     * so no peer is faked and nothing about discovery is mutated. Everything downstream is
     * production: the real [PairableControlSession.beginSecurePairing], the real secure session
     * operation, and the one shared `controlExecutor` whose four workers and four pending slots are
     * the thing under test.
     *
     * TIMING. An active pairing task blocks in the TLS handshake, which production bounds with
     * `TLS_HANDSHAKE_TIMEOUT_MILLIS` of 5s against a far end that never answers. So the window in
     * which four tasks are demonstrably active and four demonstrably queued is a few seconds wide.
     * Every "nothing has started yet" assertion below is therefore taken immediately after the
     * refusal is observed, and every positive assertion uses a bounded poll rather than a sleep.
     */
    private fun runPairingQueueMatrix(
        provider: LanPeerDiscoveryProvider,
        lease: DiscoveryLease,
        observer: CountingObserver,
        listener: RecordingListener,
    ) {
        val submittedBefore = observer.submitted.get()
        val rejectedBefore = observer.rejected.get()
        val acquiredBefore = observer.acquired.get()
        val releasedBefore = observer.released.get()
        val deliveredBefore = listener.delivered.get()
        mark("pairing_matrix_started", "sessions=$SESSION_TOTAL")

        ServerSocket(0).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val sessions = ArrayList<PairableControlSession>(SESSION_TOTAL)
            val farEnds = ArrayList<Socket>(SESSION_TOTAL)
            val outcomes = ArrayList<PairingOutcome>(SESSION_TOTAL)
            try {
                repeat(SESSION_TOTAL) { index ->
                    val near = Socket()
                    near.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    // The test holds the far end and never answers it.
                    val far = phase("accepting fixture socket ${index + 1}") { server.accept() }
                    // A short probe timeout, deliberately not PEER_READ_TIMEOUT_MILLIS. The
                    // "a queued session has begun no work" assertions have to read every
                    // queued far end and get nothing, and at four seconds a read that
                    // finds nothing would cost four seconds each and blow the test bound.
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

                // The first four go in alone, and the remaining five only after those four are
                // provably running.
                //
                // Submitting all nine in one tight loop does NOT produce "four active and four
                // queued", and run 38631866637 measured why: with four idle workers and a
                // four-slot queue, execute() offers the first four tasks to the queue before any
                // worker has dequeued them, so the queue is already full and the next submissions
                // are rejected. That run rejected four tasks instead of one. Waiting for the first
                // four to reach their sockets proves they have left the queue, which is what makes
                // the queue state deterministic rather than a race against worker wake-up.
                repeat(ACTIVE_LIMIT) { index ->
                    phase("beginning pairing on session ${index + 1}") {
                        sessions[index].beginSecurePairing(outcomes[index])
                    }
                }
                assertTrue(
                    "fewer than $ACTIVE_LIMIT pairing tasks reached their socket, so the pending " +
                        "queue cannot be filled deterministically; touched " +
                        "${farEnds.take(ACTIVE_LIMIT).count { productionTouched(it) }}",
                    awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) {
                        farEnds.take(ACTIVE_LIMIT).all { productionTouched(it) }
                    },
                )

                (ACTIVE_LIMIT until SESSION_TOTAL).forEach { index ->
                    phase("beginning pairing on session ${index + 1}") {
                        sessions[index].beginSecurePairing(outcomes[index])
                    }
                }

                assertTrue(
                    "only ${observer.submitted.get() - submittedBefore} of $SESSION_TOTAL pairing " +
                        "tasks reached the production executor",
                    awaitTrue(SUBMISSION_TIMEOUT_SECONDS) {
                        observer.submitted.get() - submittedBefore == SESSION_TOTAL
                    },
                )

                // The ninth is refused by AbortPolicy, not run and not queued.
                assertTrue(
                    "the ninth pairing never reached a terminal result; observed " +
                        "${outcomes.last().result}",
                    outcomes.last().awaitTerminal(QUEUE_REFUSAL_TIMEOUT_SECONDS),
                )
                val ninthFailure = (outcomes.last().result as? SecurePairingResult.Failed)?.failure
                assertEquals(
                    "the ninth pairing was refused with ${ninthFailure?.code} rather than a " +
                        "queue-full refusal",
                    SessionFailureCode.OPERATION_QUEUE_FULL,
                    ninthFailure?.code,
                )
                assertEquals(
                    "the production executor rejected ${observer.rejected.get() - rejectedBefore} " +
                        "tasks, expected exactly the ninth",
                    1,
                    observer.rejected.get() - rejectedBefore,
                )
                mark("ninth_pairing_refused",
                    "code=operation_queue_full rejected=${observer.rejected.get()}")

                // Four are running, four are still queued, and a queued session has begun no work.
                val startedWhileQueued = farEnds.drop(ACTIVE_LIMIT).count { productionTouched(it) }
                assertEquals(
                    "$startedWhileQueued queued pairing(s) reached their socket while all four " +
                        "workers were busy, so the pending queue is not bounding anything",
                    0,
                    startedWhileQueued,
                )
                assertEquals(
                    "the pairing path acquired ${observer.acquired.get() - acquiredBefore} " +
                        "establishment permits; pairing and establishment capacity are separate " +
                        "limits and only establishment may take one",
                    0,
                    observer.acquired.get() - acquiredBefore,
                )
                assertEquals(
                    "the pairing path released ${observer.released.get() - releasedBefore} " +
                        "establishment permits it never held",
                    0,
                    observer.released.get() - releasedBefore,
                )
                mark("pairing_queue_saturated",
                    "active=$ACTIVE_LIMIT queued=$ACTIVE_LIMIT rejected=1 permits_held=0")

                // --- mixed saturation: an establishment attempt against a full pairing queue -----
                val mixedAcquiredBefore = observer.acquired.get()
                val mixedReleasedBefore = observer.released.get()
                val mixedRejectedBefore = observer.rejected.get()
                val mixedAttempt = phase("attempting an establishment with the pairing queue full") {
                    connectPeer()
                }
                try {
                    assertTrue(
                        "the establishment attempt was not refused while the pairing queue was " +
                            "full; rejections stayed at ${observer.rejected.get() - mixedRejectedBefore}",
                        awaitTrue(MIXED_REFUSAL_TIMEOUT_SECONDS) {
                            observer.rejected.get() > mixedRejectedBefore
                        },
                    )
                    assertEquals(
                        "the saturated establishment attempt acquired " +
                            "${observer.acquired.get() - mixedAcquiredBefore} permits, expected " +
                            "exactly one before submission was attempted",
                        1,
                        observer.acquired.get() - mixedAcquiredBefore,
                    )
                    assertTrue(
                        "the saturated establishment attempt did not return its permit exactly " +
                            "once; acquired ${observer.acquired.get() - mixedAcquiredBefore}, " +
                            "released ${observer.released.get() - mixedReleasedBefore}",
                        awaitTrue(RELEASE_TIMEOUT_SECONDS) {
                            observer.released.get() - mixedReleasedBefore == 1
                        },
                    )
                    assertEquals(
                        "the saturated establishment attempt returned " +
                            "${observer.released.get() - mixedReleasedBefore} permits, expected one",
                        1,
                        observer.released.get() - mixedReleasedBefore,
                    )
                    assertTrue("the saturated establishment attempt was left connected",
                        awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(mixedAttempt) })
                    assertEquals("a session was delivered for the saturated establishment attempt",
                        deliveredBefore, listener.delivered.get())
                    mark("mixed_saturation_refused",
                        "permit_acquired=1 permit_released=1 delivered=0")
                } finally {
                    runCatching { mixedAttempt.close() }
                }

                // --- release one blocker and a queued pairing progresses -------------------------
                phase("closing session 1 to release a worker") { sessions[0].close() }
                assertTrue("no queued pairing progressed after a worker was released",
                    awaitTrue(ACTIVE_WORK_TIMEOUT_SECONDS) { productionTouched(farEnds[ACTIVE_LIMIT]) })
                mark("queued_pairing_progressed", "released_one=true")

                // --- release everything and the rest of the queued work runs ---------------------
                phase("closing every remaining session") {
                    sessions.drop(1).forEach { runCatching { it.close() } }
                }
                // Indices ACTIVE_LIMIT+1 until the last: the remaining queued sessions. The final
                // index is the refused ninth, which is asserted separately as never having run, so
                // dropping rather than slicing it out would have waited on a socket production
                // will never touch.
                assertTrue("a queued pairing never reached its socket after every worker was released",
                    awaitTrue(RELEASE_TIMEOUT_SECONDS) {
                        farEnds.subList(ACTIVE_LIMIT + 1, SESSION_TOTAL - 1)
                            .all { productionTouched(it) }
                    })

                // The refused ninth must never run late, and nothing may authenticate.
                assertTrue(
                    "the refused ninth pairing reached its socket later, so a rejected task " +
                        "executed after rejection",
                    !productionTouched(farEnds.last()),
                )
                val authenticated = outcomes.count { it.result is SecurePairingResult.Authenticated }
                assertEquals("$authenticated pairing(s) authenticated with a silent fixture peer",
                    0, authenticated)
                assertEquals("a session was delivered during the pairing matrix",
                    deliveredBefore, listener.delivered.get())
                mark("pairing_matrix_completed",
                    "rejected=${observer.rejected.get()} authenticated=0 delivered=0")
            } finally {
                sessions.forEach { runCatching { it.close() } }
                farEnds.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Receives the production terminal result for one fixture session. */
    private class PairingOutcome : SecurePairingListener {
        private val terminal = CountDownLatch(1)

        @Volatile
        var result: SecurePairingResult? = null
            private set

        override fun onApprovalRequired(request: SecurePairingApprovalRequest) {
            // A silent fixture peer can never reach an approval, and production has no
            // auto-approval implementation to reach for either.
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
        const val SESSION_TOTAL = 9
        const val ACCEPT_TIMEOUT_MILLIS = 10_000
        const val SUBMISSION_TIMEOUT_SECONDS = 8L
        const val QUEUE_REFUSAL_TIMEOUT_SECONDS = 8L
        const val ACTIVE_WORK_TIMEOUT_SECONDS = 6L
        const val MIXED_REFUSAL_TIMEOUT_SECONDS = 8L
    }
}
