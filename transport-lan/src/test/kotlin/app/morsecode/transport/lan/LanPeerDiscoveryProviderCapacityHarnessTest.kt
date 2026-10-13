package app.morsecode.transport.lan

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.transfer.session.ControlConnectionResult
import app.morsecode.core.transfer.session.DiscoveryDiagnostics
import app.morsecode.core.transfer.session.DiscoveryListener
import app.morsecode.core.transfer.session.DiscoveryPhase
import app.morsecode.core.transfer.session.DiscoveryRequest
import app.morsecode.core.transfer.session.DiscoveryStartResult
import app.morsecode.core.transfer.session.DiscoveredPeer
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.transport.lan.security.ControlEstablishmentObserver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Establishes that control-establishment capacity can be driven through the real provider.
 *
 * Every capacity scenario worth asserting runs against the production
 * [app.morsecode.transport.lan.security.SecureLanPairingCoordinator]'s owner -- the real
 * [LanPeerDiscoveryProvider], its real `controlExecutor`, its real `ArrayBlockingQueue`, its real
 * `AbortPolicy` and its real fair semaphore. None of that can be asserted unless four things hold
 * at once under Robolectric, and until now nothing in this module had ever constructed the provider
 * to find out whether they do:
 *
 *  1. `start()` reaches a started lease rather than failing on the multicast permission, the
 *     multicast lock, the fixed control-port bind or the connectivity manager.
 *  2. The bound control port actually accepts a connection, so the accept loop is live and the
 *     inbound establishment path is reachable from a test without any production change.
 *  3. The internal observation seam reports real capacity events from the production path.
 *  4. Closing the lease releases what it took, so the next test can bind the same fixed port.
 *
 * This is deliberately a small, fast test. It exists so a capacity scenario that fails later fails
 * for a reason about capacity, and not because the harness never worked in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
public class LanPeerDiscoveryProviderCapacityHarnessTest {

    private class RecordingListener : DiscoveryListener {
        override fun onDiscoveryChanged(
            phase: DiscoveryPhase,
            peers: List<DiscoveredPeer>,
            diagnostics: DiscoveryDiagnostics,
        ) = Unit

        override fun onIncomingControlSession(result: ControlConnectionResult.Connected) = Unit
    }

    @Test
    public fun realProviderStartsBindsTheControlPortAndReportsCapacityEvents() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application)
            .grantPermissions(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)

        val acquired = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val provider = LanPeerDiscoveryProvider(
            context = application,
            // A real advancing clock. Robolectric's SystemClock does not move on its own, and a
            // clock that never advances would make every deadline in the provider behave as though
            // no time had passed.
            clock = MonotonicClock { System.nanoTime() / 1_000_000L },
        )
        provider.establishmentObserver = object : ControlEstablishmentObserver {
            override fun onControlTaskSubmitted() {
                submitted.countDown()
            }

            override fun onControlPermitAcquired() {
                acquired.countDown()
            }
        }

        val result = provider.start(
            DiscoveryRequest(displayName = "Capacity Probe", appVersion = "1.0.0"),
            RecordingListener(),
        )
        assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
        val lease = (result as DiscoveryStartResult.Started).lease
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), NetworkPorts.PEER_CONTROL),
                    CONNECT_TIMEOUT_MILLIS,
                )
                assertTrue("the fixed control port did not accept a connection", socket.isConnected)
            }
            assertTrue(
                "no control permit acquisition was observed from the production accept path",
                acquired.await(OBSERVE_TIMEOUT_MILLIS, TimeUnit.SECONDS),
            )
            assertTrue(
                "no control task submission was observed from the production accept path",
                submitted.await(OBSERVE_TIMEOUT_MILLIS, TimeUnit.SECONDS),
            )
        } finally {
            lease.close()
        }

        // The fixed control port is a process-wide singleton, so a lease that leaked its server
        // socket would fail every later test with a port conflict rather than with its own reason.
        val reopened = runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), NetworkPorts.PEER_CONTROL),
                    CONNECT_TIMEOUT_MILLIS,
                )
            }
        }
        assertTrue(
            "the lease closed but the fixed control port is still accepting connections",
            reopened.isFailure,
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val OBSERVE_TIMEOUT_MILLIS = 20L
    }
}
