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
 * The smallest real control-establishment capacity lifecycle, run against the production owner.
 *
 * One lease. Four controlled establishments. A refused fifth. Four releases. A stopped lease. A
 * clean exit. Nothing else: no queue saturation, no mixed saturation, no observer-defect scenario.
 * Those wait until this terminates reliably, because a lifecycle that cannot be shown to finish
 * cannot support any larger claim built on it.
 *
 * WHY IT IS BOUNDED THREE TIMES OVER. Run 38460137506 was cancelled after hanging at this step, and
 * a JUnit timeout on its own is not a sufficient answer: interrupting a thread blocked in a native
 * or socket call may not terminate it. So the test carries its own timeout, every individual wait
 * here carries a deadline, and the whole instrumentation command is wrapped in a hard process bound
 * outside the JVM. Any one of the three converts a hang into a reportable failure.
 *
 * PHASE MARKERS. Each stage prints `A3PHASE <name> ...` to standard output, which the Gradle console
 * carries into the captured instrumentation log. On a hang the last marker names the stage that did
 * not finish, which is the only evidence available when there is no result XML and no stack. The
 * markers carry attempt aliases and counters only -- never a peer identifier or an address.
 *
 * WHY ONE LEASE. The provider binds the fixed control port with `reuseAddress = false`, so every
 * closed connection leaves that port in TIME_WAIT and a second lease in the same process fails with
 * `control_port_conflict`. The port is therefore bound exactly once per instrumentation run.
 */
@RunWith(AndroidJUnit4::class)
public class ControlEstablishmentCapacityTest {

    /**
     * Counts the four capacity events and releases a latch on a chosen acquisition threshold.
     *
     * Acquisitions and releases are counted separately: a leak shows as acquisitions exceeding
     * releases, a double release as the reverse. One combined counter could not tell them apart.
     *
     * Every method is non-blocking by construction. These callbacks run on the accept thread and on
     * control workers, so an observer that waited on anything would stall the very executor whose
     * progress the test is measuring.
     */
    private class CountingObserver : ControlEstablishmentObserver {
        val submitted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)

        private val thresholds = mutableMapOf<Int, CountDownLatch>()

        @Synchronized
        fun latchAtAcquisitions(count: Int): CountDownLatch =
            thresholds.getOrPut(count) { CountDownLatch(1) }

        @Synchronized
        fun latchAtReleases(count: Int): CountDownLatch =
            releaseThresholds.getOrPut(count) { CountDownLatch(1) }

        private val releaseThresholds = mutableMapOf<Int, CountDownLatch>()

        override fun onControlTaskSubmitted() {
            submitted.incrementAndGet()
        }

        override fun onControlTaskRejected() {
            rejected.incrementAndGet()
        }

        override fun onControlPermitAcquired() {
            val total = acquired.incrementAndGet()
            synchronized(this) { thresholds[total] }?.countDown()
        }

        override fun onControlPermitReleased() {
            val total = released.incrementAndGet()
            synchronized(this) { releaseThresholds[total] }?.countDown()
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

    /** Prints a bounded phase marker for the diagnostic step to read out of the console log. */
    private fun mark(phase: String, detail: String = "") {
        println("A3PHASE $phase${if (detail.isEmpty()) "" else " $detail"}")
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
    public fun fourEstablishmentsHoldFourPermitsTheFifthIsRefusedAndAllFourReturnExactlyOnce() {
        mark("test_cleanup_started", "scenario=establishment_capacity")
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

            mark("lease_start")
            val result = phase("starting the discovery lease") {
                provider.start(
                    DiscoveryRequest(displayName = "Capacity", appVersion = "1.0.0"),
                    listener,
                )
            }
            assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
            lease = (result as DiscoveryStartResult.Started).lease
            mark("listener_bound", "peers=0 permits=0")

            // --- four establishments, each holding one real permit -------------------------------
            // A peer that connects and says nothing is the blocker. The accept loop takes the permit
            // and submits the task before any byte is read, and the task's first act is
            // readHandshake, which blocks -- so the worker is parked after real permit acquisition
            // and before any handshake work, with no cryptographic involvement.
            repeat(ACTIVE_LIMIT) { index ->
                val alias = "peer-${index + 1}"
                mark("attempt_submitted", "attempt=$alias")
                peers += phase("connecting $alias") {
                    connectPeer().also {
                        assertTrue("the fixed control port did not accept $alias", it.isConnected)
                    }
                }
            }
            assertTrue(
                "only ${observer.acquired.get()} of $ACTIVE_LIMIT control permits were acquired",
                fourAcquired.await(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            mark(
                "permit_acquired",
                "active=${observer.acquired.get()} held=${observer.acquired.get() - observer.released.get()} " +
                    "submissions=${observer.submitted.get()} open_sockets=${peers.size}",
            )
            assertEquals(
                "four peers produced ${observer.submitted.get()} submissions, expected $ACTIVE_LIMIT",
                ACTIVE_LIMIT, observer.submitted.get(),
            )
            mark("establishment_blocked", "active=$ACTIVE_LIMIT held=$ACTIVE_LIMIT")

            // --- the fifth is refused before the executor is consulted ---------------------------
            // The fair semaphore is the gate, so this is CONTROL_CAPACITY_REACHED and not a queue
            // rejection: the bounded queue is never reached, and calling it OPERATION_QUEUE_FULL
            // would describe a path this attempt never took.
            mark("fifth_attempt_started", "attempt=peer-5")
            val fifth = phase("connecting peer-5") { connectPeer() }
            peers += fifth
            val refused = awaitTrue(REFUSAL_TIMEOUT_SECONDS) { peerEndIsClosed(fifth) }
            assertTrue("peer-5 was not closed by production, so it reached the executor", refused)
            mark(
                "fifth_attempt_refused",
                "result=inbound_socket_refused active=${observer.acquired.get()} " +
                    "submissions=${observer.submitted.get()} rejections=${observer.rejected.get()}",
            )
            assertEquals(
                "a fifth control permit was acquired; capacity exceeded its bound",
                ACTIVE_LIMIT, observer.acquired.get(),
            )
            assertEquals("a fifth task was submitted to the control executor",
                ACTIVE_LIMIT, observer.submitted.get())
            assertEquals("the refused peer must not be reported as a queue rejection",
                0, observer.rejected.get())
            assertEquals("a silent peer must not be delivered as a control session",
                0, listener.delivered.get())

            // --- release all four blockers; each permit returns exactly once ---------------------
            mark("blocker_release_started", "open_sockets=${peers.size}")
            peers.forEach { runCatching { it.close() } }
            peers.clear()
            assertTrue(
                "closing every blocker returned ${observer.released.get()} permits, " +
                    "expected $ACTIVE_LIMIT",
                fourReleased.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            mark("permit_released", "released=${observer.released.get()} acquired=${observer.acquired.get()}")
            assertEquals("permits did not settle at zero",
                observer.acquired.get(), observer.released.get())
            assertEquals("a terminal callback fired for a peer that never completed a handshake",
                0, listener.delivered.get())

            // --- stop the lease ------------------------------------------------------------------
            mark("lease_stop_started")
            phase("closing the lease") { lease.close() }
            lease = null
            mark("lease_stop_completed")
        } finally {
            lease?.let { runCatching { it.close() } }
            peers.forEach { runCatching { it.close() } }
            peers.clear()
            mark(
                "test_cleanup_completed",
                "acquired=${observer.acquired.get()} released=${observer.released.get()} " +
                    "submissions=${observer.submitted.get()} rejections=${observer.rejected.get()} " +
                    "delivered=${listener.delivered.get()}",
            )
        }

        // The fixed control port is a process-wide singleton, so a lease that kept its server socket
        // would fail every later test with a port conflict instead of its own reason.
        assertTrue(
            "the lease stopped but the fixed control port is still accepting connections",
            awaitTrue(PORT_FREE_TIMEOUT_SECONDS) {
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
        const val RELEASE_TIMEOUT_SECONDS = 12L
        const val PORT_FREE_TIMEOUT_SECONDS = 15L
        const val POLL_INTERVAL_MILLIS = 10L
        const val FRAME_LIMIT = 12
        const val TEST_TIMEOUT_MILLIS = 120_000L
    }
}
