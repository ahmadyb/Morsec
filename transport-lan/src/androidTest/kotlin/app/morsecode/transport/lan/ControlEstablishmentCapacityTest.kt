package app.morsecode.transport.lan

import android.app.Application
import android.util.Log
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
import app.morsecode.core.transfer.session.SessionFailureCode
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
    }
}
