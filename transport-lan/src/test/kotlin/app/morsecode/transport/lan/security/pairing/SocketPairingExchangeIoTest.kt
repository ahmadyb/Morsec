/*
 * The production frame transport over a socket, on the JVM.
 *
 * Every other pairing case runs over an in-memory channel, which is what makes the protocol tests
 * fast and deterministic -- and also why the socket class's own framing arithmetic had never run
 * until a device did it. The first frame a pairing sends exposed an off-by-one in the prefix read:
 * the remaining four bytes were requested at offset one of a four-byte array, which is an
 * `IndexOutOfBoundsException` raised from inside the TLS provider rather than a frame. A loopback
 * socket, with no TLS and no emulator, exercises the same code on the JVM.
 *
 * What is asserted here is only framing: a frame written is the frame read, whatever the peer's
 * writes are broken up into. Whether the exchange accepts that frame is [PairingAttemptExchangeTest].
 *
 * STATUS: experimental, project-defined, not independently cryptographically reviewed. See
 * doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SocketPairingExchangeIoTest {

    private val clock = TickingClock()

    @Test(timeout = 20_000L)
    fun aFrameCrossesARealSocketIntact() {
        withSocketPair { reader, writer ->
            val sent = frame(64, marker = 0x11)
            assertTrue(SocketPairingExchangeIo(writer, clock).writeFrame("first", sent, 5_000L))

            val read = SocketPairingExchangeIo(reader, clock).readFrame("first", 5_000L)

            assertTrue("expected a frame, got $read", read is PairingFrameRead.Frame)
            assertArrayEquals(sent, (read as PairingFrameRead.Frame).bytes)
        }
    }

    @Test(timeout = 20_000L)
    fun twoFramesArriveOneAfterTheOther() {
        withSocketPair { reader, writer ->
            val io = SocketPairingExchangeIo(writer, clock)
            val first = frame(24, marker = 0x21)
            val second = frame(96, marker = 0x22)
            assertTrue(io.writeFrame("first", first, 5_000L))
            assertTrue(io.writeFrame("second", second, 5_000L))

            val readerIo = SocketPairingExchangeIo(reader, clock)
            val one = readerIo.readFrame("first", 5_000L)
            val two = readerIo.readFrame("second", 5_000L)

            assertTrue("expected the first frame, got $one", one is PairingFrameRead.Frame)
            assertTrue("expected the second frame, got $two", two is PairingFrameRead.Frame)
            assertArrayEquals(first, (one as PairingFrameRead.Frame).bytes)
            assertArrayEquals(second, (two as PairingFrameRead.Frame).bytes)
        }
    }

    @Test(timeout = 20_000L)
    fun aFrameDribbledByteByByteStillArrivesWhole() {
        withSocketPair { reader, writer ->
            val sent = frame(128, marker = 0x31)
            // Written one byte at a time, on another thread, so the reader's reads are partial ones
            // at a moving offset: the shape the prefix read got wrong.
            val dribbler = Thread {
                val output = writer.getOutputStream()
                for (byte in sent) {
                    output.write(byte.toInt())
                    output.flush()
                    Thread.sleep(1L)
                }
            }.apply {
                isDaemon = true
                start()
            }

            val read = SocketPairingExchangeIo(reader, clock).readFrame("dribbled", 5_000L)
            dribbler.join(5_000L)

            assertTrue("expected a frame, got $read", read is PairingFrameRead.Frame)
            assertArrayEquals(sent, (read as PairingFrameRead.Frame).bytes)
        }
    }

    /**
     * The shape the exchange actually produces: one frame read in short slices.
     *
     * The exchange polls in slices so it can notice cancellation, so a slice routinely expires
     * part-way through a frame. That progress has to survive the call. Losing it would make the
     * rest of the frame look like a new length prefix -- a permanent desync of a stream the peer
     * wrote correctly, which on a device showed up as a protocol refusal of an honest pairing.
     */
    @Test(timeout = 20_000L)
    fun aFrameInterruptedByASliceDeadlineIsFinishedRatherThanRestarted() {
        withSocketPair { reader, writer ->
            val sent = frame(payloadSize = 300, marker = 0x41)
            // The peer writes the prefix and the first bytes, then pauses for longer than a slice
            // before the rest. Both pauses are inside the frame, so a reader that drops what it has
            // read cannot recover.
            val pauseMillis = 150L
            val split = 2
            val dribbler = Thread {
                val output = writer.getOutputStream()
                output.write(sent, 0, split)
                output.flush()
                Thread.sleep(pauseMillis)
                output.write(sent, split, 4 - split)
                output.flush()
                Thread.sleep(pauseMillis)
                output.write(sent, 4, sent.size - 4)
                output.flush()
            }.apply {
                isDaemon = true
                start()
            }

            val io = SocketPairingExchangeIo(reader, clock)
            var read: PairingFrameRead = PairingFrameRead.TimedOut
            var calls = 0
            // The same contract the exchange follows: poll in 50 ms slices against a total budget.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                calls += 1
                read = io.readFrame("sliced", 50L)
                if (read !is PairingFrameRead.TimedOut) break
            }
            dribbler.join(5_000L)

            assertTrue("expected a frame, got $read after $calls calls", read is PairingFrameRead.Frame)
            assertArrayEquals(sent, (read as PairingFrameRead.Frame).bytes)
            assertTrue("the slice deadline must have been hit, took $calls calls", calls > 1)

            // And the stream is still in step: the next frame is read from its own prefix.
            val next = frame(payloadSize = 40, marker = 0x42)
            val output = writer.getOutputStream()
            output.write(next)
            output.flush()
            val second = io.readFrame("after-slices", 5_000L)
            assertTrue("expected the following frame, got $second", second is PairingFrameRead.Frame)
            assertArrayEquals(next, (second as PairingFrameRead.Frame).bytes)
        }
    }

    @Test(timeout = 20_000L)
    fun silenceIsADeadlineAndNotAnEmptyFrame() {
        withSocketPair { reader, _ ->
            val started = System.nanoTime()
            val read = SocketPairingExchangeIo(reader, clock).readFrame("silence", 300L)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

            assertEquals("silence is a deadline, got $read", PairingFrameRead.TimedOut, read)
            assertTrue("the budget must bound the wait, took ${elapsedMillis}ms", elapsedMillis < 5_000L)
        }
    }

    @Test(timeout = 20_000L)
    fun aPeerThatClosedBeforeSendingAnythingIsACleanEnd() {
        withSocketPair { reader, writer ->
            writer.close()

            val read = SocketPairingExchangeIo(reader, clock).readFrame("closed", 5_000L)

            assertEquals("a clean close is not a deadline, got $read", PairingFrameRead.EndOfStream, read)
        }
    }

    /**
     * Runs [block] on a connected loopback pair. The first socket is the reader's, the second the
     * writer's; both remain open unless the block closes them.
     */
    private fun withSocketPair(block: (reader: Socket, writer: Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = AtomicReference<Socket>()
            val acceptor = Thread { accepted.set(runCatching { server.accept() }.getOrNull()) }.apply {
                isDaemon = true
                start()
            }
            Socket().use { client ->
                client.connect(server.localSocketAddress, 5_000)
                acceptor.join(5_000L)
                val serverSide = requireNotNull(accepted.get()) { "the loopback connection was not accepted" }
                try {
                    block(client, serverSide)
                } finally {
                    runCatching { serverSide.close() }
                }
            }
        }
    }

    /** A length-prefixed frame the exchange's own reader will accept. */
    private fun frame(payloadSize: Int, marker: Int): ByteArray {
        val payload = ByteArray(payloadSize) { index -> (marker + index).toByte() }
        val frame = ByteArray(4 + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        payload.copyInto(frame, 4)
        return frame
    }
}
