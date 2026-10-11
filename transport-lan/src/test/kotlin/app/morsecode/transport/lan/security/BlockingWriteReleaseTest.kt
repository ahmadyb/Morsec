package app.morsecode.transport.lan.security

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-socket evidence that closing a socket releases a write blocked by backpressure.
 *
 * `soTimeout` does not apply to writes, so the only thing that can release a thread parked in
 * `write` is closing the socket it is writing to. A fake clock cannot demonstrate that: it can
 * show a timer fired, but not that a real kernel buffer stopped accepting bytes and then started
 * failing them. This test uses a genuine loopback socket pair and a peer that never reads.
 *
 * Bounded by construction: one 4 KiB chunk is reused rather than allocating a large payload, the
 * write loop has an iteration ceiling, and every wait has a timeout.
 */
public class BlockingWriteReleaseTest {

    private companion object {
        /** Exceeds any plausible sum of send and receive buffers, so TCP flow control must engage. */
        const val CHUNK_BYTES = 4_096
        const val MAX_CHUNKS = 8_192 // 32 MiB written through a reused buffer
    }

    @Test
    public fun closingTheSocketReleasesAWriteBlockedByBackpressure() {
        val listener = ServerSocket()
        listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        var peer: Socket? = null
        val client = Socket()
        val released = CountDownLatch(1)
        val outcome = AtomicReference<String>("running")
        var writer: Thread? = null
        try {
            client.connect(listener.localSocketAddress, 5_000)
            // Deliberately small, and set before any data moves so the kernel honours it.
            client.sendBufferSize = 8_192
            peer = listener.accept()
            peer.soTimeout = 1_000
            peer.receiveBufferSize = 8_192
            // The peer never reads. Everything it is sent has to stall in the buffers.

            val socket = client
            writer = Thread {
                try {
                    val out = socket.getOutputStream()
                    val chunk = ByteArray(CHUNK_BYTES)
                    var sent = 0
                    while (sent < MAX_CHUNKS) {
                        out.write(chunk)
                        out.flush()
                        sent += 1
                    }
                    outcome.set("completed")
                } catch (_: IOException) {
                    outcome.set("released-by-close")
                } catch (_: RuntimeException) {
                    outcome.set("released-by-close")
                } finally {
                    released.countDown()
                }
            }
            writer.isDaemon = true
            writer.start()

            val stillBlocked = !released.await(3, TimeUnit.SECONDS)
            assertTrue(
                "32 MiB into a peer that never reads must reach backpressure, not complete",
                stillBlocked,
            )

            // This is the operation under test: nothing else touches the socket.
            socket.close()

            assertTrue(
                "closing the socket must release the blocked write within a bounded wait",
                released.await(5, TimeUnit.SECONDS),
            )
            assertEquals("released-by-close", outcome.get())
        } finally {
            writer?.let { if (it.isAlive) it.interrupt() }
            runCatching { client.close() }
            runCatching { peer?.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    public fun aWriteIsNotReportedAsSuccessfulAfterTheSocketIsClosed() {
        val listener = ServerSocket()
        listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        var peer: Socket? = null
        val client = Socket()
        try {
            client.connect(listener.localSocketAddress, 5_000)
            peer = listener.accept()
            client.close()
            // A closed socket must not let a later write report success. Whatever it throws, the
            // caller sees a failure -- which is the property the channel's expiry check encodes.
            val failed = runCatching {
                val out = client.getOutputStream()
                repeat(64) {
                    out.write(ByteArray(65_536))
                    out.flush()
                }
            }.isFailure
            assertTrue("writing to a closed socket must fail rather than silently succeed", failed)
        } finally {
            runCatching { client.close() }
            runCatching { peer?.close() }
            runCatching { listener.close() }
        }
    }
}
