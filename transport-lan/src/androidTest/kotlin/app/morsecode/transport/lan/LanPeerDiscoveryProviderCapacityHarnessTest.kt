package app.morsecode.transport.lan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.transfer.session.ControlConnectionResult
import app.morsecode.core.transfer.session.DiscoveryDiagnostics
import app.morsecode.core.transfer.session.DiscoveryListener
import app.morsecode.core.transfer.session.DiscoveryPhase
import app.morsecode.core.transfer.session.DiscoveryRequest
import app.morsecode.core.transfer.session.DiscoveryStartResult
import app.morsecode.core.transfer.session.DiscoveredPeer
import app.morsecode.transport.lan.security.ControlEstablishmentObserver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Establishes that control-establishment capacity can be driven through the real provider.
 *
 * Every capacity scenario worth asserting runs against the production owner -- the real
 * [LanPeerDiscoveryProvider], its real `controlExecutor`, its real `ArrayBlockingQueue`, its real
 * `AbortPolicy` and its real fair semaphore. None of that can be asserted unless a started lease, a
 * reachable control port and a working observation seam all exist at once.
 *
 * THIS RUNS ON A DEVICE, NOT UNDER ROBOLECTRIC, AND THAT IS NOT A PREFERENCE. A Robolectric version
 * of this test was tried first and failed with `UnsatisfiedLinkError: no conscrypt_jni in
 * java.library.path`, in both unit test variants. Conscrypt ships native libraries for Android ABIs
 * and none for the host JVM, and Robolectric's Android security stack reaches that provider during
 * sandbox setup -- the failure escaped a guard wrapped around every phase of the test body, which is
 * how it was located outside it. So the capacity harness belongs where the native library exists,
 * alongside the other real-TLS pairing tests in this source set.
 *
 * Each phase still reports the throwing frame. There is no local toolchain here, so a failure that
 * names only an exception type costs a whole CI run to learn nothing about its cause.
 */
@RunWith(AndroidJUnit4::class)
public class LanPeerDiscoveryProviderCapacityHarnessTest {

    private class RecordingListener : DiscoveryListener {
        override fun onDiscoveryChanged(
            phase: DiscoveryPhase,
            peers: List<DiscoveredPeer>,
            diagnostics: DiscoveryDiagnostics,
        ) = Unit

        override fun onIncomingControlSession(result: ControlConnectionResult.Connected) = Unit
    }

    /** Runs one phase, and on any throwable fails with the phase name and its top frames. */
    private fun <T> phase(name: String, body: () -> T): T = try {
        body()
    } catch (thrown: Throwable) {
        val frames = thrown.stackTrace.take(FRAME_LIMIT).joinToString("\n  ") { "at $it" }
        val cause = thrown.cause?.let { "\ncaused by: $it" } ?: ""
        fail("$name threw $thrown$cause\n  $frames")
        throw thrown
    }

    @Test
    public fun realProviderStartsBindsTheControlPortAndReportsCapacityEvents() {
        val application = phase("obtaining the instrumentation application") {
            ApplicationProvider.getApplicationContext<Application>()
        }

        val acquired = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val provider = phase("constructing the provider") {
            LanPeerDiscoveryProvider(context = application)
        }
        phase("installing the observation seam") {
            provider.establishmentObserver = object : ControlEstablishmentObserver {
                override fun onControlTaskSubmitted() {
                    submitted.countDown()
                }

                override fun onControlPermitAcquired() {
                    acquired.countDown()
                }
            }
        }

        val result = phase("starting the discovery lease") {
            provider.start(
                DiscoveryRequest(displayName = "Capacity Probe", appVersion = "1.0.0"),
                RecordingListener(),
            )
        }
        assertTrue("expected a started lease but got $result", result is DiscoveryStartResult.Started)
        val lease = (result as DiscoveryStartResult.Started).lease
        try {
            phase("connecting to the fixed control port") {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), NetworkPorts.PEER_CONTROL),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    assertTrue("the fixed control port did not accept a connection", socket.isConnected)
                }
            }
            assertTrue(
                "no control permit acquisition was observed from the production accept path",
                acquired.await(OBSERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(
                "no control task submission was observed from the production accept path",
                submitted.await(OBSERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            phase("closing the lease") { lease.close() }
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
        const val OBSERVE_TIMEOUT_SECONDS = 30L
        const val FRAME_LIMIT = 12
    }
}
