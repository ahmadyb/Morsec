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
 * WHY A SILENT PEER IS THE RIGHT BLOCKER. The accept loop acquires a permit and submits the task
 * before any byte is read, and the task's first act is `readHandshake`, which blocks on the socket.
 * A peer that connects and sends nothing therefore parks a worker *after* real permit acquisition
 * and *before* any handshake work, which is exactly the phase these scenarios have to observe. No
 * cryptographic work is involved, so nothing here depends on Conscrypt behaviour.
 *
 * THE WINDOW IS FIVE SECONDS AND THAT IS PRODUCTION'S NUMBER, NOT THE TEST'S. The accepted socket is
 * given `soTimeout = CONTROL_TIMEOUT_MILLIS`, so a silent peer holds its permit for at most that
 * long. Every wait here is a bounded latch on an observed event rather than a sleep, and the
 * sequence is short enough to finish inside that window.
 *
 * WHAT THIS FILE DOES NOT CLAIM. The outbound `connectSelected` path, the pairing-executor queue and
 * mixed saturation all need completed control sessions, which need an advertisement in the peer
 * registry. That is a separate question and is not asserted here.
 */
@RunWith(AndroidJUnit4::class)
public class ControlEstablishmentCapacityTest {

    /**
     * Counts the four capacity events and releases a latch on each threshold.
     *
     * Acquisitions and releases are counted separately on purpose: a leak shows as acquisitions
     * exceeding releases, and a double release shows as the reverse. A single "capacity changed"
     * counter could not tell those apart.
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

        private fun noteAcquisition() {
            val total = acquired.incrementAndGet()
            val latch = synchronized(this) { thresholds[total] }
            latch?.countDown()
        }

        override fun onControlTaskSubmitted() {
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on submission")
            submitted.incrementAndGet()
        }

        override fun onControlTaskRejected() {
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on rejection")
            rejected.incrementAndGet()
        }

        override fun onControlPermitAcquired() {
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on acquisition")
            noteAcquisition()
        }

        override fun onControlPermitReleased() {
            if (throwOnEveryEvent) throw IllegalStateException("observer defect on release")
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

    private fun startedLease(
        application: Application,
        observer: ControlEstablishmentObserver,
    ): Pair<DiscoveryLease, RecordingListener> {
        val provider = phase("constructing the provider") {
            LanPeerDiscoveryProvider(context = application)
        }
        phase("installing the observation seam") {
            provider.establishmentObserver = observer
        }
        val listener = RecordingListener()
        val result = phase("starting the discovery lease") {
            provider.start(
                DiscoveryRequest(displayName = "Capacity", appVersion = "1.0.0"),
                listener,
            )
        }
        assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
        return (result as DiscoveryStartResult.Started).lease to listener
    }

    private fun connectPeer(): Socket = Socket().apply {
        soTimeout = PEER_READ_TIMEOUT_MILLIS
        connect(controlEndpoint(), CONNECT_TIMEOUT_MILLIS)
    }

    private fun controlEndpoint(): SocketAddress =
        InetSocketAddress(InetAddress.getLoopbackAddress(), NetworkPorts.PEER_CONTROL)

    /** True when production has closed its end, which is how a refused socket is detected. */
    private fun peerEndIsClosed(socket: Socket): Boolean = try {
        socket.getInputStream().read() == -1
    } catch (_: java.io.IOException) {
        true
    }

    @Test
    public fun fourSilentPeersHoldEveryPermitAndTheFifthIsRefusedBeforeSubmission() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val observer = CountingObserver()
        val (lease, listener) = startedLease(application, observer)
        val peers = mutableListOf<Socket>()
        try {
            // Registered before the first peer connects. A threshold latch installed afterwards
            // would miss the acquisitions that already happened and wait for events that passed.
            val fourAcquired = phase("installing the four-acquisition latch") {
                observer.latchAtAcquisitions(ACTIVE_LIMIT)
            }
            repeat(ACTIVE_LIMIT) { index ->
                peers += phase("connecting silent peer ${index + 1}") { connectPeer() }
            }
            assertTrue(
                "only ${observer.acquired.get()} of $ACTIVE_LIMIT control permits were acquired",
                fourAcquired.await(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            // The four permits are all held, so the fair semaphore must refuse the fifth *before*
            // it can reach the executor. This is CONTROL_CAPACITY_REACHED, not a queue rejection:
            // the queue is never consulted, and claiming OPERATION_QUEUE_FULL here would be wrong.
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
                "permits went negative or exceeded the bound: " +
                    "acquired=${observer.acquired.get()} released=${observer.released.get()}",
                observer.released.get() in 0..ACTIVE_LIMIT,
            )

            // Free one and prove the capacity is reusable rather than permanently consumed.
            val before = observer.released.get()
            phase("closing the first silent peer") { peers[0].close() }
            assertTrue(
                "closing a silent peer returned ${observer.released.get() - before} permits, " +
                    "expected exactly one",
                awaitTrue(PERMIT_TIMEOUT_SECONDS) { observer.released.get() == before + 1 },
            )
            val sixth = phase("connecting a replacement peer") { connectPeer() }
            peers += sixth
            assertTrue(
                "the freed capacity did not admit a further peer",
                awaitTrue(PERMIT_TIMEOUT_SECONDS) { observer.acquired.get() == ACTIVE_LIMIT + 1 },
            )
            assertEquals(
                "a replacement peer was refused as a queue rejection",
                0, observer.rejected.get(),
            )
        } finally {
            phase("closing the lease") { lease.close() }
            peers.forEach { runCatching { it.close() } }
        }

        // Every permit that was taken comes back exactly once. More releases than acquisitions would
        // mean a double release; fewer would mean a permanent reduction in pairing capacity.
        assertTrue(
            "permits did not settle: acquired=${observer.acquired.get()} " +
                "released=${observer.released.get()}",
            awaitTrue(DRAIN_TIMEOUT_SECONDS) {
                observer.released.get() == observer.acquired.get()
            },
        )
        assertEquals("a terminal callback fired for a peer that never completed a handshake",
            0, listener.delivered.get())
    }

    @Test
    public fun anObserverThatThrowsOnEveryEventChangesNothingAndLeaksNoPermit() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val observer = CountingObserver().apply { throwOnEveryEvent = true }
        val (lease, listener) = startedLease(application, observer)
        val peers = mutableListOf<Socket>()
        try {
            // The production wrapper swallows observer defects, so none of these counters can ever
            // advance. What matters is that the *scheduling* is unchanged: the same four permits are
            // taken, the fifth is still refused, and nothing is delivered.
            repeat(ACTIVE_LIMIT) { index ->
                peers += phase("connecting silent peer ${index + 1} with a throwing observer") {
                    connectPeer()
                }
            }
            val fifth = phase("connecting the fifth silent peer with a throwing observer") {
                connectPeer()
            }
            peers += fifth
            assertTrue(
                "the fifth peer was not closed by production while the observer was throwing",
                awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(fifth) },
            )
            assertEquals("a throwing observer recorded events", 0, observer.acquired.get())
            assertEquals("a throwing observer recorded submissions", 0, observer.submitted.get())
            assertEquals(
                "a silent peer was delivered as a session while the observer was throwing",
                0, listener.delivered.get(),
            )
        } finally {
            phase("closing the lease with a throwing observer installed") { lease.close() }
            peers.forEach { runCatching { it.close() } }
        }

        // The lease still stops cleanly: the fixed control port is a process-wide singleton, so a
        // lease that failed to release its server socket would fail every later test.
        val reopened = runCatching {
            Socket().use { it.connect(controlEndpoint(), CONNECT_TIMEOUT_MILLIS) }
        }
        assertTrue(
            "the lease closed but the fixed control port is still accepting connections",
            reopened.isFailure,
        )
    }

    /** Polls a condition with a bounded deadline instead of sleeping for a guessed duration. */
    private fun awaitTrue(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val ACTIVE_LIMIT = 4
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val PEER_READ_TIMEOUT_MILLIS = 4_000
        const val PERMIT_TIMEOUT_SECONDS = 4L
        const val REFUSAL_TIMEOUT_SECONDS = 4L
        const val DRAIN_TIMEOUT_SECONDS = 12L
        const val POLL_INTERVAL_MILLIS = 10L
        const val FRAME_LIMIT = 12
    }
}
