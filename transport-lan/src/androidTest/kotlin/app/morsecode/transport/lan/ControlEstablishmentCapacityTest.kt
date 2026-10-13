package app.morsecode.transport.lan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.transfer.session.ControlConnectionResult
import app.morsecode.core.transfer.session.DiscoveryDiagnostics
import app.morsecode.core.transfer.session.DiscoveryLease
import app.morsecode.core.transfer.session.DiscoveryListener
import app.morsecode.core.transfer.session.DiscoveryPhase
import app.morsecode.core.transfer.session.DiscoveryRequest
import app.morsecode.core.transfer.session.DiscoveryStartResult
import app.morsecode.core.transfer.session.DiscoveredPeer
import app.morsecode.transport.lan.security.ControlEstablishmentObserver
import java.net.InetAddress
import java.net.InetSocketAddress
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
 * Control-establishment capacity, measured on the production owner.
 *
 * These run against the real [LanPeerDiscoveryProvider]: its real `controlExecutor`, its real
 * `ArrayBlockingQueue`, its real `AbortPolicy`, its real fair `Semaphore(4)` and its real
 * exactly-once release guards. Only the peer is controlled -- it is a socket that says nothing.
 *
 * WHY ONE LEASE FOR THE WHOLE SEQUENCE. The provider binds the fixed control port with
 * `reuseAddress = false`, which is deliberate: two applications must not be able to hold the peer
 * control port at once. The cost is that any closed connection leaves that port in TIME_WAIT, so a
 * second lease in the same process cannot bind it and fails with `control_port_conflict`. That is
 * what happened when the harness and these scenarios lived in separate classes. So the lease is
 * created once, every scenario runs against it, and the port is bound exactly once per run.
 *
 * WHY A SILENT PEER IS THE RIGHT BLOCKER. The accept loop acquires a permit and submits the task
 * before any byte is read, and the task's first act is `readHandshake`, which blocks on the socket.
 * A peer that connects and sends nothing therefore parks a worker *after* real permit acquisition
 * and *before* any handshake work -- exactly the phase these scenarios observe, with no
 * cryptographic involvement at all.
 *
 * THE BLOCKING WINDOW IS FIVE SECONDS AND THAT IS PRODUCTION'S NUMBER. The accepted socket is given
 * `soTimeout = CONTROL_TIMEOUT_MILLIS`, so a silent peer holds its permit for at most that long. The
 * capacity phase is therefore kept short and every wait is a bounded latch on an observed event.
 * The observer-defect phase runs after the permits have drained, so it is not racing that window.
 *
 * WHAT THIS FILE DOES NOT CLAIM. The outbound `connectSelected` path, the pairing-executor queue and
 * mixed saturation need completed control sessions, which need an advertisement in the peer
 * registry. That is a separate question and is not asserted here.
 */
@RunWith(AndroidJUnit4::class)
public class ControlEstablishmentCapacityTest {

    /**
     * Counts the four capacity events and releases a latch on a chosen acquisition threshold.
     *
     * Acquisitions and releases are counted separately on purpose: a leak shows as acquisitions
     * exceeding releases, and a double release shows as the reverse. One combined counter could not
     * tell those apart.
     */
    private class CountingObserver : ControlEstablishmentObserver {
        val submitted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)

        @Volatile
        var throwOnEveryEvent: Boolean = false

        private val thresholds = mutableMapOf<Int, CountDownLatch>()

        @Synchronized
        fun latchAtAcquisitions(count: Int): CountDownLatch =
            thresholds.getOrPut(count) { CountDownLatch(1) }

        private fun guard(event: String) {
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on $event")
        }

        override fun onControlTaskSubmitted() {
            guard("submission")
            submitted.incrementAndGet()
        }

        override fun onControlTaskRejected() {
            guard("rejection")
            rejected.incrementAndGet()
        }

        override fun onControlPermitAcquired() {
            guard("acquisition")
            val total = acquired.incrementAndGet()
            synchronized(this) { thresholds[total] }?.countDown()
        }

        override fun onControlPermitReleased() {
            guard("release")
            released.incrementAndGet()
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

    private fun <T> phase(name: String, body: () -> T): T = try {
        body()
    } catch (thrown: Throwable) {
        val frames = thrown.stackTrace.take(FRAME_LIMIT).joinToString("\n  ") { "at $it" }
        val cause = thrown.cause?.let { "\ncaused by: $it" } ?: ""
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

    /** Polls a condition against a deadline rather than sleeping for a guessed duration. */
    private fun awaitTrue(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    @Test
    public fun establishmentCapacityHoldsAtFourRefusesTheFifthAndRecoversExactlyOnce() {
        val application = phase("obtaining the instrumentation application") {
            ApplicationProvider.getApplicationContext<Application>()
        }
        val observer = CountingObserver()
        val listener = RecordingListener()
        val provider = phase("constructing the provider") {
            LanPeerDiscoveryProvider(context = application)
        }
        phase("installing the observation seam") {
            provider.establishmentObserver = observer
        }
        // Registered before any peer connects: a threshold latch installed afterwards would miss the
        // acquisitions that already happened and wait for events that had already passed.
        val fourAcquired = phase("installing the four-acquisition latch") {
            observer.latchAtAcquisitions(ACTIVE_LIMIT)
        }

        val result = phase("starting the discovery lease") {
            provider.start(
                DiscoveryRequest(displayName = "Capacity", appVersion = "1.0.0"),
                listener,
            )
        }
        assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
        val lease: DiscoveryLease = (result as DiscoveryStartResult.Started).lease
        val peers = mutableListOf<Socket>()
        try {
            // --- four active establishments, each holding one real permit ------------------------
            // The first of these is also the proof that the fixed control port is reachable: a
            // separate probe peer is deliberately not used, because it would take one of the four
            // permits and silently change what "the fifth" means.
            repeat(ACTIVE_LIMIT) { index ->
                peers += phase("connecting silent peer ${index + 1}") {
                    connectPeer().also {
                        assertTrue("the fixed control port did not accept a connection", it.isConnected)
                    }
                }
            }
            assertTrue(
                "only ${observer.acquired.get()} of $ACTIVE_LIMIT control permits were acquired",
                fourAcquired.await(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertEquals(
                "four peers produced ${observer.submitted.get()} submissions, expected $ACTIVE_LIMIT",
                ACTIVE_LIMIT, observer.submitted.get(),
            )

            // --- the fifth is refused before the executor is consulted ---------------------------
            // The fair semaphore is the gate, so this is CONTROL_CAPACITY_REACHED and not a queue
            // rejection: the bounded queue is never reached, and reporting OPERATION_QUEUE_FULL here
            // would describe a path the attempt never took.
            val fifth = phase("connecting the fifth silent peer") { connectPeer() }
            peers += fifth
            assertTrue(
                "the fifth peer was not closed by production, so it reached the executor",
                awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(fifth) },
            )
            assertEquals(
                "a fifth control permit was acquired; capacity exceeded its bound",
                ACTIVE_LIMIT, observer.acquired.get(),
            )
            assertEquals(
                "a fifth task was submitted to the control executor",
                ACTIVE_LIMIT, observer.submitted.get(),
            )
            assertEquals(
                "the refused peer must not be reported as a queue rejection",
                0, observer.rejected.get(),
            )
            assertEquals(
                "a silent peer must not be delivered as a control session",
                0, listener.delivered.get(),
            )
            assertTrue(
                "permits left the 0..$ACTIVE_LIMIT bound: " +
                    "acquired=${observer.acquired.get()} released=${observer.released.get()}",
                observer.released.get() in 0..ACTIVE_LIMIT,
            )

            // --- freeing one returns exactly one permit and the capacity is reusable -------------
            val releasesBefore = observer.released.get()
            phase("closing the first silent peer") { peers[0].close() }
            assertTrue(
                "closing one silent peer returned ${observer.released.get() - releasesBefore} " +
                    "permits, expected exactly one",
                awaitTrue(PERMIT_TIMEOUT_SECONDS) { observer.released.get() == releasesBefore + 1 },
            )
            val replacement = phase("connecting a replacement peer") { connectPeer() }
            peers += replacement
            assertTrue(
                "the freed capacity did not admit a further peer",
                awaitTrue(PERMIT_TIMEOUT_SECONDS) { observer.acquired.get() == ACTIVE_LIMIT + 1 },
            )
            assertEquals("a replacement peer was refused as a queue rejection",
                0, observer.rejected.get())

            // --- drain: every permit taken comes back exactly once -------------------------------
            peers.forEach { runCatching { it.close() } }
            peers.clear()
            assertTrue(
                "permits did not settle: acquired=${observer.acquired.get()} " +
                    "released=${observer.released.get()}",
                awaitTrue(DRAIN_TIMEOUT_SECONDS) {
                    observer.released.get() == observer.acquired.get()
                },
            )
            assertEquals(
                "a terminal callback fired for a peer that never completed a handshake",
                0, listener.delivered.get(),
            )

            // --- an observer that throws must not change scheduling ------------------------------
            // The production wrapper swallows observer defects, so nothing can be counted from here
            // on. What is still observable is the scheduling itself: four permits are taken, and the
            // fifth peer is still closed by production rather than admitted.
            observer.throwOnEveryEvent = true
            val throwingPeers = mutableListOf<Socket>()
            try {
                repeat(ACTIVE_LIMIT) { index ->
                    throwingPeers += phase("connecting silent peer ${index + 1} " +
                        "with a throwing observer") { connectPeer() }
                }
                val refused = phase("connecting the fifth silent peer with a throwing observer") {
                    connectPeer()
                }
                throwingPeers += refused
                assertTrue(
                    "the fifth peer was not closed by production while the observer was throwing",
                    awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(refused) },
                )
                assertEquals(
                    "a silent peer was delivered as a session while the observer was throwing",
                    0, listener.delivered.get(),
                )
            } finally {
                throwingPeers.forEach { runCatching { it.close() } }
                observer.throwOnEveryEvent = false
            }
        } finally {
            phase("closing the lease") { lease.close() }
            peers.forEach { runCatching { it.close() } }
        }

        // The fixed control port is a process-wide singleton. A lease that failed to release its
        // server socket would fail every later test with a port conflict instead of its own reason,
        // which is precisely the failure that forced these scenarios into a single lease.
        assertTrue(
            "the lease closed but the fixed control port is still accepting connections",
            awaitTrue(DRAIN_TIMEOUT_SECONDS) {
                runCatching {
                    Socket().use { it.connect(controlEndpoint(), CONNECT_TIMEOUT_MILLIS) }
                }.isFailure
            },
        )
    }

    private companion object {
        const val ACTIVE_LIMIT = 4
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val PEER_READ_TIMEOUT_MILLIS = 4_000
        const val PERMIT_TIMEOUT_SECONDS = 4L
        const val REFUSAL_TIMEOUT_SECONDS = 4L
        const val DRAIN_TIMEOUT_SECONDS = 15L
        const val POLL_INTERVAL_MILLIS = 10L
        const val FRAME_LIMIT = 12
    }
}
