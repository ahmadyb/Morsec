package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameType
import app.morsecode.core.transfer.protocol.TransferFrame
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.payload.LanPayloadProtectResult
import app.morsecode.transport.lan.payload.LanPayloadRecordFormat
import app.morsecode.transport.lan.payload.LanPayloadRecordLayer
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The multiplexer, driven over real loopback sockets.
 *
 * WHY REAL SOCKETS
 *   The failures that matter here are about bytes arriving in pieces, records queueing behind each
 *   other, and deadlines firing inside a record. A byte-array pair would hand a whole record over
 *   atomically and would never exercise the polling loop. Each test owns a connected socket pair and
 *   reads through the same code production would run.
 *
 * WHAT IS REAL AND WHAT IS SUBSTITUTED
 *   Real: sockets, the reader, the writer arbiter, both record layers, and AES-GCM through the JDK
 *   provider rather than Conscrypt (the only difference from the device).
 *   Substituted: three test doubles, each labelled where it is defined -- a socket whose writes can
 *   fail or block part way, a seal that can be held open, and an AEAD that counts its closes.
 *
 * NOTHING HERE IS A PAYLOAD PATH
 *   Every record is produced and consumed inside this test. The multiplexer has no production call
 *   site, the payload gate still refuses every session, and the provider installs the control-only
 *   channel.
 */
class LanMultiplexedRecordChannelTest {

    // --- ordering ---------------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun aControlRecordThenAPayloadRecordArriveInOrder() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
            assertTrue(control.sendPayloadFrame(harness.frame(8)) is LanMultiplexedSend.Sent)

            assertControl(peer.receiveNext(harness.deadline()), SecureRecordType.PING)
            assertPayload(peer.receiveNext(harness.deadline()), harness.frame(8))
        }
    }

    @Test(timeout = 30_000L)
    fun aPayloadRecordThenAControlRecordArriveInOrder() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            assertTrue(control.sendPayloadFrame(harness.frame(8)) is LanMultiplexedSend.Sent)
            assertTrue(control.sendControl(SecureRecordType.PONG) is LanMultiplexedSend.Sent)

            assertPayload(peer.receiveNext(harness.deadline()), harness.frame(8))
            assertControl(peer.receiveNext(harness.deadline()), SecureRecordType.PONG)
        }
    }

    @Test(timeout = 60_000L)
    fun alternatingRecordsArriveInOrder() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            repeat(3) { index ->
                assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
                assertTrue(
                    control.sendPayloadFrame(harness.frame(4, sequence = index.toLong()))
                        is LanMultiplexedSend.Sent,
                )
            }
            repeat(3) { index ->
                assertControl(peer.receiveNext(harness.deadline()), SecureRecordType.PING)
                assertPayload(
                    peer.receiveNext(harness.deadline()),
                    harness.frame(4, sequence = index.toLong()),
                )
            }
        }
    }

    // --- fragmentation ----------------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun aPrefixSplitAfterEveryByteStillParses() {
        for (splitAfter in 1..4) {
            Harness().use { harness ->
                val wire = harness.peerControlWire(SecureRecordType.PING)
                val writer = harness.writeInPiecesAsync(wire, listOf(splitAfter), 80L)
                val reader = harness.standaloneClient()
                assertControl(reader.receiveNext(harness.deadline()), SecureRecordType.PING)
                writer.join(5_000L)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun aHeaderSplitAtEveryBoundaryStillParses() {
        for (boundary in listOf(4, 5, 20, 34)) {
            Harness().use { harness ->
                val wire = harness.peerPayloadWire(harness.frame(8))
                val writer = harness.writeInPiecesAsync(wire, listOf(boundary), 80L)
                val reader = harness.standaloneClient()
                assertPayload(reader.receiveNext(harness.deadline()), harness.frame(8))
                writer.join(5_000L)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun aBodyAndTagSplitAcrossSlicesStillParses() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWire(harness.frame(64))
            val cuts = listOf(34, 40, 60, wire.size - 1)
            val writer = harness.writeInPiecesAsync(wire, cuts, 70L)
            val reader = harness.standaloneClient()
            assertPayload(reader.receiveNext(harness.deadline()), harness.frame(64))
            writer.join(5_000L)
        }
    }

    /**
     * The regression this file exists for.
     *
     * Two bytes are written, the reader is given four polling slices to give up, and only then is the
     * rest written. An implementation that cleared its prefix or restarted the header read after a
     * slice would see the wrong magic or a short header here.
     */
    @Test(timeout = 60_000L)
    fun aPartiallyReadPrefixSurvivesThePollingSlice() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING)
            val writer = harness.writeInPiecesAsync(wire, listOf(2), 220L)
            val reader = harness.standaloneClient()
            assertControl(reader.receiveNext(harness.deadline(5_000L)), SecureRecordType.PING)
            writer.join(5_000L)
        }
    }

    @Test(timeout = 60_000L)
    fun aSlowButProgressingPeerIsNotTimedOut() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWire(harness.frame(96))
            val cuts = (1..7).map { it * (wire.size / 8) }
            // Eight pieces about 70 ms apart is 560 ms: well past the 50 ms slice, well inside the
            // absolute deadline. The slice is a cancellation poll, not the operation's lifetime.
            val writer = harness.writeInPiecesAsync(wire, cuts, 70L)
            val reader = harness.standaloneClient()
            assertPayload(reader.receiveNext(harness.deadline(5_000L)), harness.frame(96))
            writer.join(5_000L)
        }
    }

    // --- deadlines and cancellation ---------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun anIdlePeerTimesOutAtTheBoundaryAndKeepsTheStream() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            val timedOut = peer.receiveNext(harness.deadline(150L))
            assertTrue("expected a timeout, got $timedOut", timedOut is LanMultiplexedReceive.TimedOut)
            assertTrue(
                "no byte was consumed, so the stream still has a boundary",
                (timedOut as LanMultiplexedReceive.TimedOut).streamUsable,
            )
            assertTrue("the channel stays open", peer.isOpen())

            assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
            assertControl(peer.receiveNext(harness.deadline(5_000L)), SecureRecordType.PING)
        }
    }

    @Test(timeout = 30_000L)
    fun aDeadlineInsideARecordIsTerminal() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING)
            harness.writeNow(wire, length = 6)
            val reader = harness.standaloneClient()

            val timedOut = reader.receiveNext(harness.deadline(150L))
            assertTrue("expected a timeout, got $timedOut", timedOut is LanMultiplexedReceive.TimedOut)
            assertFalse(
                "a stream whose next byte is inside a record has no boundary",
                (timedOut as LanMultiplexedReceive.TimedOut).streamUsable,
            )
            assertFalse("the channel is closed", reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aCancellationAtTheBoundaryKeepsTheStream() {
        Harness().use { harness ->
            val cancelled = AtomicBoolean(false)
            harness.startChannels(isCancelled = { cancelled.get() })
            val peer = requireNotNull(harness.peerChannel)
            cancelled.set(true)
            val outcome = peer.receiveNext(harness.deadline(5_000L))
            assertTrue("expected a cancellation, got $outcome", outcome is LanMultiplexedReceive.Cancelled)
            assertTrue((outcome as LanMultiplexedReceive.Cancelled).streamUsable)
            assertTrue(peer.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aCancellationInsideARecordIsTerminal() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING)
            harness.writeNow(wire, length = 6)
            val cancelled = AtomicBoolean(false)
            val reader = harness.standaloneClient(isCancelled = { cancelled.get() })
            cancelled.set(true)
            val outcome = reader.receiveNext(harness.deadline(5_000L))
            assertTrue("expected a cancellation, got $outcome", outcome is LanMultiplexedReceive.Cancelled)
            assertFalse((outcome as LanMultiplexedReceive.Cancelled).streamUsable)
            assertFalse(reader.isOpen())
        }
    }

    // --- framing failures -------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun anUnknownMagicIsTerminal() {
        Harness().use { harness ->
            val reader = harness.standaloneClient()
            harness.writeRaw(byteArrayOf(0x4D, 0x53, 0x52, 0x32))
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aPayloadRecordPresentedAsControlIsRefused() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWire(harness.frame(16)).copyOf()
            // A genuine MPL1 record with its magic rewritten to MSR1. The control decoder reads the
            // shared session and direction fields, then finds a record type of zero -- a length byte
            // from the payload header -- and refuses before any body is sized.
            harness.msr1Magic().copyInto(wire, 0)

            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aControlRecordPresentedAsPayloadIsRefused() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING).copyOf()
            LanPayloadRecordFormat.magicBytes().copyInto(wire, 0)

            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            // Read as MPL1, the control record type becomes the top byte of the declared length, so
            // the length is tens of megabytes and the format refuses it before allocating anything.
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aDeclaredLengthAboveTheMaximumIsRefusedBeforeAllocation() {
        Harness().use { harness ->
            val header = ByteArray(LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
            LanPayloadRecordFormat.magicBytes().copyInto(header, 0)
            header[LanPayloadRecordFormat.OFF_VERSION] = LanPayloadRecordFormat.WIRE_VERSION.toByte()
            LanPayloadRecordFormat.requireSessionIdBytes(harness.sessionId)
                .copyInto(header, LanPayloadRecordFormat.OFF_SESSION_ID)
            header[LanPayloadRecordFormat.OFF_DIRECTION] = 2
            for (index in 0 until LanPayloadRecordFormat.DECLARED_LENGTH_BYTES) {
                header[LanPayloadRecordFormat.OFF_DECLARED_LENGTH + index] = 0xFF.toByte()
            }
            val reader = harness.standaloneClient()
            harness.writeNow(header)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 60_000L)
    fun aTruncatedRecordEndsInATypedTerminalFailure() {
        val truncations = listOf(2, 10, LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES + 5)
        for (length in truncations) {
            Harness().use { harness ->
                val wire = harness.peerPayloadWire(harness.frame(32))
                harness.writeNow(wire, length)
                // A clean end of stream inside a record is not a boundary either.
                harness.closePeerSocket()
                val reader = harness.standaloneClient()
                assertRefused(
                    reader.receiveNext(harness.deadline()),
                    SessionFailureCode.CONTROL_CONNECT_FAILED,
                )
                assertFalse(reader.isOpen())
            }
        }
    }

    // --- authentication ---------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun aControlTagFailureClosesTheChannel() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING).copyOf()
            wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x01).toByte()
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aPayloadTagFailureClosesTheChannel() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWire(harness.frame(8)).copyOf()
            wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x01).toByte()
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
            // A payload failure must take the control half of the stream with it.
            assertTrue(reader.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Refused)
        }
    }

    @Test(timeout = 30_000L)
    fun aPayloadRecordForAnotherSessionIsRefused() {
        Harness().use { harness ->
            val foreign = harness.newPayloadLayer(
                sessionId = SessionId("fedcba9876543210fedcba9876543210"),
                role = SecurePeerRole.RESPONDER,
                sendKey = Harness.PAYLOAD_S2C,
            )
            val wire = (foreign.protect(FrameCodec.encode(harness.frame(8)))
                as LanPayloadProtectResult.Protected).wireRecord
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aReflectedPayloadRecordIsRefused() {
        Harness().use { harness ->
            // A layer that thinks it is the initiator sends initiator-to-responder traffic. The
            // reading initiator expects responder-to-initiator, so the direction field alone refuses
            // it, before the tag is tried.
            val reflected = harness.newPayloadLayer(
                sessionId = harness.sessionId,
                role = SecurePeerRole.INITIATOR,
                sendKey = Harness.PAYLOAD_C2S,
            )
            val wire = (reflected.protect(FrameCodec.encode(harness.frame(8)))
                as LanPayloadProtectResult.Protected).wireRecord
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aReplayedControlRecordIsRefused() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.PING)
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertControl(reader.receiveNext(harness.deadline()), SecureRecordType.PING)
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aPayloadSequenceGapIsRefused() {
        Harness().use { harness ->
            val layer = harness.peerPayload()
            // Spend record zero without sending it, then send record one. The reader expects zero
            // and refuses the gap rather than buffering out of order.
            layer.protect(FrameCodec.encode(harness.frame(8)))
            val second = (layer.protect(FrameCodec.encode(harness.frame(8, sequence = 1L)))
                as LanPayloadProtectResult.Protected).wireRecord
            val reader = harness.standaloneClient()
            harness.writeNow(second)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    // --- the MSC1 boundary ------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun anInvalidNestedFrameIsRefused() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWireFor(ByteArray(32) { 0x5A })
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun anEmptyNestedFrameIsRefused() {
        Harness().use { harness ->
            val wire = harness.peerPayloadWireFor(ByteArray(0))
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun twoNestedFramesInOnePlaintextAreRefused() {
        Harness().use { harness ->
            val first = FrameCodec.encode(harness.frame(8))
            val second = FrameCodec.encode(harness.frame(8, sequence = 1L))
            val both = ByteArray(first.size + second.size)
            first.copyInto(both, 0)
            second.copyInto(both, first.size)
            val wire = harness.peerPayloadWireFor(both)
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 60_000L)
    fun theLargestFrameTheProfileAllowsRoundTrips() {
        Harness().use { harness ->
            // 44-byte MSC1 header + 32-byte session id + two 64-byte identifiers + a 65,568-byte
            // chunk is exactly the 65,772-byte MPL1 plaintext ceiling: the largest record the profile
            // can carry, not merely a large one.
            val chunk = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES - 204) {
                (it * 31).toByte()
            }
            val largest = FrameCodec.dataChunk(
                sessionId = harness.sessionId,
                transferId = TransferId("t".repeat(64)),
                offset = 0L,
                sequence = 0L,
                bytes = chunk,
                recipientId = RecipientId("r".repeat(64)),
            )
            val encoded = FrameCodec.encode(largest)
            assertEquals(
                "the frame must fill the MPL1 plaintext ceiling exactly",
                LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES,
                encoded.size,
            )

            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)
            assertTrue(control.sendPayloadFrame(largest) is LanMultiplexedSend.Sent)

            val received = peer.receiveNext(harness.deadline(10_000L))
            assertTrue("expected a payload record, got $received", received is LanMultiplexedReceive.Payload)
            val frame = (received as LanMultiplexedReceive.Payload).frame
            assertTrue(frame.payloadEquals(largest))
            assertEquals(largest.sequence, frame.sequence)
            assertEquals(largest.offset, frame.offset)
        }
    }

    @Test(timeout = 30_000L)
    fun aFrameLargerThanThePayloadRecordCeilingIsRefusedLocally() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            // 44 + 32 + 64 + 64 = 204 bytes of framing; 65,569 bytes of payload puts the encoding one
            // byte past the 65,772-byte MPL1 plaintext ceiling. The channel refuses it before taking
            // the writer, so no sequence is spent and the stream stays usable.
            val oversized = FrameCodec.dataChunk(
                sessionId = harness.sessionId,
                transferId = TransferId("t".repeat(64)),
                offset = 0L,
                sequence = 0L,
                bytes = ByteArray(65_569),
                recipientId = RecipientId("r".repeat(64)),
            )
            val encoded = FrameCodec.encode(oversized)
            assertTrue(
                "the test frame must exceed the ceiling, was ${encoded.size}",
                encoded.size > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES,
            )

            val outcome = control.sendPayloadFrame(oversized)
            assertTrue("expected a local refusal, got $outcome", outcome is LanMultiplexedSend.Refused)
            assertEquals(
                SessionFailureCode.INVALID_REQUEST,
                (outcome as LanMultiplexedSend.Refused).failure.code,
            )
            assertTrue("a local refusal does not close the channel", control.isOpen())

            // Nothing was spent: the next payload record still carries sequence zero.
            assertTrue(control.sendPayloadFrame(harness.frame(8)) is LanMultiplexedSend.Sent)
            assertPayload(
                requireNotNull(harness.peerChannel).receiveNext(harness.deadline()),
                harness.frame(8),
            )
        }
    }

    // --- concurrency ------------------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun concurrentControlSendsDoNotInterleave() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)
            val senders = 8
            val each = 5
            runConcurrently(senders) {
                repeat(each) {
                    assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
                }
            }
            // Every record has to decrypt and the control sequence has to stay contiguous: one
            // interleaved byte anywhere would break the very next header.
            repeat(senders * each) {
                assertControl(peer.receiveNext(harness.deadline(10_000L)), SecureRecordType.PING)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun concurrentPayloadSendsDoNotInterleave() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)
            val senders = 6
            val each = 3
            runConcurrently(senders) { threadIndex ->
                repeat(each) { round ->
                    val frame = harness.frame(16, sequence = (threadIndex * 100 + round).toLong())
                    assertTrue(
                        "send failed for $threadIndex/$round",
                        control.sendPayloadFrame(frame) is LanMultiplexedSend.Sent,
                    )
                }
            }
            repeat(senders * each) {
                val received = peer.receiveNext(harness.deadline(10_000L))
                assertTrue("expected a payload record, got $received", received is LanMultiplexedReceive.Payload)
                val frame = (received as LanMultiplexedReceive.Payload).frame
                assertTrue("the nested frame is intact", frame.payloadIsIntact())
            }
        }
    }

    @Test(timeout = 60_000L)
    fun concurrentControlAndPayloadSendsDoNotInterleave() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            val controlThreads = 4
            val payloadThreads = 4
            val controlEach = 4
            val payloadEach = 4
            runConcurrently(controlThreads + payloadThreads) { index ->
                if (index < controlThreads) {
                    repeat(controlEach) {
                        assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
                    }
                } else {
                    repeat(payloadEach) {
                        assertTrue(
                            control.sendPayloadFrame(harness.frame(12, sequence = index.toLong()))
                                is LanMultiplexedSend.Sent,
                        )
                    }
                }
            }

            var controls = 0
            var payloads = 0
            repeat(controlThreads * controlEach + payloadThreads * payloadEach) {
                when (val received = peer.receiveNext(harness.deadline(10_000L))) {
                    is LanMultiplexedReceive.Control -> controls++
                    is LanMultiplexedReceive.Payload -> {
                        assertTrue("the nested frame is intact", received.frame.payloadIsIntact())
                        payloads++
                    }
                    else -> throw AssertionError("unexpected record: $received")
                }
            }
            assertEquals(controlThreads * controlEach, controls)
            assertEquals(payloadThreads * payloadEach, payloads)
        }
    }

    /**
     * A control record cannot interrupt the record already being written, and it wins the next slot.
     *
     * The payload seal is held open by a gate, the only way to make "a record is in progress"
     * deterministic on loopback. While it is open a control send starts and must queue. When the gate
     * opens the payload record finishes, and the control record is the very next one on the stream.
     */
    @Test(timeout = 60_000L)
    fun aControlRecordWaitsForTheRecordInProgressThenWinsTheNextSlot() {
        Harness().use { harness ->
            val gate = GatedAead(jcaAead(Harness.PAYLOAD_C2S))
            harness.startChannels(payloadSendAead = gate)
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            val payloadThread = Thread { control.sendPayloadFrame(harness.frame(8)) }
            payloadThread.start()
            assertTrue("the payload seal must have started", gate.awaitEntered())

            val controlThread = Thread { control.sendControl(SecureRecordType.PING) }
            controlThread.start()
            Thread.sleep(150L)
            assertTrue("the control record must still be waiting", controlThread.isAlive)

            gate.release()
            payloadThread.join(10_000L)
            controlThread.join(10_000L)
            assertFalse(payloadThread.isAlive)
            assertFalse(controlThread.isAlive)

            assertPayload(peer.receiveNext(harness.deadline(5_000L)), harness.frame(8))
            assertControl(peer.receiveNext(harness.deadline(5_000L)), SecureRecordType.PING)
        }
    }

    /**
     * The documented bound, exercised: a payload record waiting behind a control burst is scheduled
     * within 32 control records of the writer becoming free.
     *
     * A single control thread sends more records than the bound while the writer is held by a gated
     * payload seal, and a second payload record is registered before the burst starts. When the gate
     * opens, the payload record must not be left behind all forty control records.
     */
    @Test(timeout = 60_000L)
    fun aWaitingPayloadRecordIsNotStarvedByAControlBurst() {
        Harness().use { harness ->
            val gate = GatedAead(jcaAead(Harness.PAYLOAD_C2S))
            harness.startChannels(payloadSendAead = gate)
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)

            val firstPayload = Thread { control.sendPayloadFrame(harness.frame(8, sequence = 0L)) }
            firstPayload.start()
            assertTrue("the first payload seal must have started", gate.awaitEntered())

            // Registered while the first record still owns the writer.
            val secondPayload = Thread { control.sendPayloadFrame(harness.frame(8, sequence = 1L)) }
            secondPayload.start()
            Thread.sleep(200L)
            assertTrue("the second payload record must be waiting", secondPayload.isAlive)

            val controls = Thread {
                repeat(40) { control.sendControl(SecureRecordType.PING) }
            }
            controls.start()
            Thread.sleep(200L)

            gate.release()
            firstPayload.join(15_000L)
            secondPayload.join(15_000L)
            controls.join(15_000L)
            assertFalse(firstPayload.isAlive)
            assertFalse("the waiting payload record must finish", secondPayload.isAlive)
            assertFalse(controls.isAlive)

            assertPayload(peer.receiveNext(harness.deadline(10_000L)), harness.frame(8, sequence = 0L))
            var controlsBeforeSecondPayload = 0
            var secondSeen = false
            while (controlsBeforeSecondPayload <= 33) {
                when (val record = peer.receiveNext(harness.deadline(10_000L))) {
                    is LanMultiplexedReceive.Control -> controlsBeforeSecondPayload++
                    is LanMultiplexedReceive.Payload -> {
                        assertEquals(1L, record.frame.sequence)
                        secondSeen = true
                        break
                    }
                    else -> throw AssertionError("unexpected record: $record")
                }
            }
            assertTrue("the waiting payload record must be scheduled", secondSeen)
            assertTrue(
                "at most 32 control records may precede it, saw $controlsBeforeSecondPayload",
                controlsBeforeSecondPayload <= 32,
            )
        }
    }

    @Test(timeout = 60_000L)
    fun concurrentReceiversEachGetOneWholeRecord() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val peer = requireNotNull(harness.peerChannel)
            assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
            assertTrue(control.sendPayloadFrame(harness.frame(8)) is LanMultiplexedSend.Sent)

            val results = arrayOfNulls<LanMultiplexedReceive>(2)
            val start = CountDownLatch(1)
            val threads = (0 until 2).map { index ->
                Thread {
                    start.await()
                    results[index] = peer.receiveNext(harness.deadline(5_000L))
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join(10_000L) }

            assertNotNull(results[0])
            assertNotNull(results[1])
            val kinds = results.map { it!!::class.java.simpleName }.toSet()
            assertEquals(setOf("Control", "Payload"), kinds)
        }
    }

    // --- write faults -----------------------------------------------------------------------------

    /**
     * A partial write is terminal, and the record is never offered again.
     *
     * The fake socket accepts ten bytes of a control frame and then throws. A retry would increment
     * the write-attempt counter and put a second copy on the stream.
     */
    @Test(timeout = 30_000L)
    fun aPartialWriteIsTerminalAndNeverRetried() {
        val stream = FailingOutputStream(writeLimit = 10, exception = IOException("simulated"))
        val fake = FakeSocket(stream)
        val harness = Harness()
        try {
            val (controlLayer, payloadLayer) = harness.layersFor(SecurePeerRole.INITIATOR)
            val channel = LanMultiplexedRecordChannel(
                socket = fake,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.INITIATOR,
                clock = harness.clock,
                expiresAtElapsedMillis = harness.expiresAt(),
            )
            val first = channel.sendControl(SecureRecordType.PING)
            assertTrue("expected a refusal, got $first", first is LanMultiplexedSend.Refused)
            assertEquals(
                SessionFailureCode.CONTROL_CONNECT_FAILED,
                (first as LanMultiplexedSend.Refused).failure.code,
            )
            assertEquals(1, stream.writeAttempts.get())
            assertFalse(channel.isOpen())

            val second = channel.sendControl(SecureRecordType.PONG)
            assertTrue(second is LanMultiplexedSend.Refused)
            assertEquals(
                "a later writer sees the same terminal cause",
                SessionFailureCode.CONTROL_CONNECT_FAILED,
                (second as LanMultiplexedSend.Refused).failure.code,
            )
            assertEquals("the record must not be retried", 1, stream.writeAttempts.get())
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aWriteTimeoutIsTerminal() {
        val stream = FailingOutputStream(writeLimit = 8, exception = SocketTimeoutException("simulated"))
        val fake = FakeSocket(stream)
        val harness = Harness()
        try {
            val (controlLayer, payloadLayer) = harness.layersFor(SecurePeerRole.INITIATOR)
            val channel = LanMultiplexedRecordChannel(
                socket = fake,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.INITIATOR,
                clock = harness.clock,
                expiresAtElapsedMillis = harness.expiresAt(),
            )
            val outcome = channel.sendControl(SecureRecordType.PING)
            assertTrue("expected a refusal, got $outcome", outcome is LanMultiplexedSend.Refused)
            assertEquals(
                SessionFailureCode.CONTROL_TIMEOUT,
                (outcome as LanMultiplexedSend.Refused).failure.code,
            )
            assertEquals(1, stream.writeAttempts.get())
            assertFalse(channel.isOpen())
        } finally {
            harness.close()
        }
    }

    /**
     * Close releases a write that is stuck in the socket, and the writer queued behind it.
     *
     * The blocking stream stands in for a send buffer that filled because the peer stopped reading;
     * nothing but closing the socket can release it. Close must therefore never wait for the write
     * lock the blocked writer holds.
     */
    @Test(timeout = 60_000L)
    fun closeReleasesABlockedWriteAndTheWriterQueuedBehindIt() {
        val stream = BlockingOutputStream()
        val fake = FakeSocket(stream)
        val harness = Harness()
        try {
            val (controlLayer, payloadLayer) = harness.layersFor(SecurePeerRole.INITIATOR)
            val channel = LanMultiplexedRecordChannel(
                socket = fake,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.INITIATOR,
                clock = harness.clock,
                expiresAtElapsedMillis = harness.expiresAt(),
            )
            val blocked = Thread { channel.sendControl(SecureRecordType.PING) }
            blocked.start()
            assertTrue("the write must have reached the socket", stream.awaitEntered())

            val waiting = Thread { channel.sendControl(SecureRecordType.PONG) }
            waiting.start()
            Thread.sleep(150L)
            assertTrue("the second writer must be waiting", waiting.isAlive)

            // Must return rather than deadlock behind the writer holding the lock.
            channel.close()
            blocked.join(5_000L)
            waiting.join(5_000L)
            assertFalse("close must release the blocked write", blocked.isAlive)
            assertFalse("close must release the waiting writer", waiting.isAlive)
            assertEquals("the socket is closed once", 1, fake.closeCount.get())
        } finally {
            harness.close()
        }
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun closeIsIdempotentAndClosesEverythingExactlyOnce() {
        val harness = Harness()
        try {
            val counter = AtomicInteger(0)
            val controlLayer = SecureRecordLayer(
                sessionId = harness.sessionId,
                localRole = SecurePeerRole.INITIATOR,
                sendAead = CountingAead(jcaAead(Harness.CONTROL_C2S), counter),
                receiveAead = CountingAead(jcaAead(Harness.CONTROL_S2C), counter),
                sendNonceBase = Harness.CONTROL_IV_C2S.copyOf(),
                receiveNonceBase = Harness.CONTROL_IV_S2C.copyOf(),
                clock = harness.clock,
            )
            val payloadLayer = LanPayloadRecordLayer(
                sessionId = harness.sessionId,
                localRole = SecurePeerRole.INITIATOR,
                sendAead = CountingAead(jcaAead(Harness.PAYLOAD_C2S), counter),
                receiveAead = CountingAead(jcaAead(Harness.PAYLOAD_S2C), counter),
                sendNonceBase = Harness.PAYLOAD_IV_C2S.copyOf(),
                receiveNonceBase = Harness.PAYLOAD_IV_S2C.copyOf(),
                clock = harness.clock,
                expiresAtElapsedMillis = harness.expiresAt(),
            )
            val fake = FakeSocket(RecordingOutputStream())
            val channel = LanMultiplexedRecordChannel(
                socket = fake,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.INITIATOR,
                clock = harness.clock,
                expiresAtElapsedMillis = harness.expiresAt(),
            )

            channel.close()
            channel.close()
            channel.close()

            assertEquals("each AEAD closes once", 4, counter.get())
            assertEquals("the socket closes once", 1, fake.closeCount.get())
            assertFalse(channel.isOpen())
        } finally {
            harness.close()
        }
    }

    @Test(timeout = 30_000L)
    fun theChannelPrintsNothingItCarries() {
        Harness().use { harness ->
            harness.startChannels()
            val text = requireNotNull(harness.clientChannel).toString()
            assertTrue("the channel must be redacted: $text", text.contains("[redacted]"))
            assertFalse(text.contains(Harness.SESSION_HEX))
            assertFalse(text.contains("MSR1"))
            assertFalse(text.contains("MPL1"))
            assertFalse(text.contains("PING"))
        }
    }

    @Test(timeout = 30_000L)
    fun aReflectedControlRecordIsRefusedByDirection() {
        Harness().use { harness ->
            // A responder-to-initiator record offered to the responder, which expects the opposite
            // direction. The direction byte is checked before the tag.
            val wire = harness.peerControlWire(SecureRecordType.PING)
            val reader = harness.standalonePeer()
            harness.writeFromClient(wire)
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
            )
            assertFalse(reader.isOpen())
        }
    }

    @Test(timeout = 30_000L)
    fun aPairingRecordCannotBeSentAfterConfirmation() {
        Harness().use { harness ->
            harness.startChannels()
            val control = requireNotNull(harness.clientChannel)
            val outcome = control.sendControl(SecureRecordType.KEY_CONFIRMATION)
            assertTrue("expected a local refusal, got $outcome", outcome is LanMultiplexedSend.Refused)
            assertEquals(
                SessionFailureCode.INVALID_REQUEST,
                (outcome as LanMultiplexedSend.Refused).failure.code,
            )
            // Local misuse spends nothing and does not take the connection down.
            assertTrue(control.isOpen())
            assertTrue(control.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Sent)
        }
    }

    @Test(timeout = 30_000L)
    fun aPeerSessionCloseIsATypedTerminal() {
        Harness().use { harness ->
            val wire = harness.peerControlWire(SecureRecordType.SESSION_CLOSE)
            val reader = harness.standaloneClient()
            harness.writeNow(wire)
            // The record is read and authenticated, but there is no record after it, so the outcome is
            // a terminal refusal rather than a record handed back from a closed channel.
            assertRefused(
                reader.receiveNext(harness.deadline()),
                SessionFailureCode.OPERATION_CANCELLED,
            )
            assertFalse(reader.isOpen())
            assertTrue(reader.sendControl(SecureRecordType.PING) is LanMultiplexedSend.Refused)
        }
    }

    // --- assertions -------------------------------------------------------------------------------

    private fun assertControl(outcome: LanMultiplexedReceive, type: SecureRecordType) {
        assertTrue("expected a control record, got $outcome", outcome is LanMultiplexedReceive.Control)
        assertEquals(type, (outcome as LanMultiplexedReceive.Control).record.type)
    }

    private fun assertPayload(outcome: LanMultiplexedReceive, expected: TransferFrame) {
        assertTrue("expected a payload record, got $outcome", outcome is LanMultiplexedReceive.Payload)
        val frame = (outcome as LanMultiplexedReceive.Payload).frame
        assertArrayEquals(expected.payload, frame.payload)
        assertEquals(expected.type, frame.type)
        assertEquals(expected.sequence, frame.sequence)
        assertEquals(expected.offset, frame.offset)
    }

    private fun assertRefused(outcome: LanMultiplexedReceive, code: SessionFailureCode) {
        assertTrue("expected a refusal, got $outcome", outcome is LanMultiplexedReceive.Refused)
        assertEquals(code, (outcome as LanMultiplexedReceive.Refused).failure.code)
    }

    private fun runConcurrently(threads: Int, body: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { index ->
            Thread {
                try {
                    start.await()
                    body(index)
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000L) }
        assertTrue("a worker did not finish", workers.none { it.isAlive })
        val first = failures.poll()
        if (first != null) throw AssertionError("a worker failed: $first", first)
    }

    // --- harness ----------------------------------------------------------------------------------

    /**
     * A connected socket pair plus the layers both ends would have after pairing.
     *
     * The control layers are primed past key confirmation by exchanging one confirmation record each
     * way through the layers themselves, never through the socket: after pairing no confirmation is
     * legal, so every later record here is PING, PONG or SESSION_CLOSE.
     */
    private class Harness : Closeable {

        val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val sessionId: SessionId = SessionId(SESSION_HEX)

        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val clientSocket = Socket().apply {
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), listener.localPort), 5_000)
            soTimeout = 5_000
        }
        private val peerSocket = listener.accept().apply { soTimeout = 5_000 }

        var clientChannel: LanMultiplexedRecordChannel? = null
            private set
        var peerChannel: LanMultiplexedRecordChannel? = null
            private set

        fun expiresAt(): Long = clock.nowMillis() + SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS

        fun deadline(extraMillis: Long = 3_000L): Long = clock.nowMillis() + extraMillis

        fun frame(payloadSize: Int, sequence: Long = 0L): TransferFrame = FrameCodec.frame(
            type = FrameType.DATA_CHUNK,
            sessionId = sessionId,
            transferId = TransferId("transfer-1"),
            sequence = sequence,
            offset = 0L,
            payload = ByteArray(payloadSize) { (it * 7).toByte() },
        )

        fun newPayloadLayer(
            sessionId: SessionId,
            role: SecurePeerRole,
            sendKey: ByteArray,
            expiresAtElapsedMillis: Long = expiresAt(),
        ): LanPayloadRecordLayer = LanPayloadRecordLayer(
            sessionId = sessionId,
            localRole = role,
            sendAead = jcaAead(sendKey),
            receiveAead = jcaAead(if (role == SecurePeerRole.INITIATOR) PAYLOAD_S2C else PAYLOAD_C2S),
            sendNonceBase = if (role == SecurePeerRole.INITIATOR) PAYLOAD_IV_C2S else PAYLOAD_IV_S2C,
            receiveNonceBase = if (role == SecurePeerRole.INITIATOR) PAYLOAD_IV_S2C else PAYLOAD_IV_C2S,
            clock = clock,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        )

        /** The peer's control layer, primed past confirmation and ready to send at sequence one. */
        fun peerControl(): SecureRecordLayer = controlPair().second

        /** The peer's payload layer, ready to send responder-to-initiator from sequence zero. */
        fun peerPayload(): LanPayloadRecordLayer =
            newPayloadLayer(sessionId, SecurePeerRole.RESPONDER, PAYLOAD_S2C)

        fun peerControlWire(type: SecureRecordType): ByteArray =
            (peerControl().protect(type, ByteArray(0)) as SecureRecordResult.Encoded).frameBytes()

        fun peerPayloadWire(frame: TransferFrame): ByteArray =
            (peerPayload().protect(FrameCodec.encode(frame))
                as LanPayloadProtectResult.Protected).wireRecord

        fun peerPayloadWireFor(plaintext: ByteArray): ByteArray =
            (peerPayload().protect(plaintext) as LanPayloadProtectResult.Protected).wireRecord

        fun controlPair(): Pair<SecureRecordLayer, SecureRecordLayer> {
            val initiator = SecureRecordLayer(
                sessionId = sessionId,
                localRole = SecurePeerRole.INITIATOR,
                sendAead = jcaAead(CONTROL_C2S),
                receiveAead = jcaAead(CONTROL_S2C),
                sendNonceBase = CONTROL_IV_C2S.copyOf(),
                receiveNonceBase = CONTROL_IV_S2C.copyOf(),
                clock = clock,
            )
            val responder = SecureRecordLayer(
                sessionId = sessionId,
                localRole = SecurePeerRole.RESPONDER,
                sendAead = jcaAead(CONTROL_S2C),
                receiveAead = jcaAead(CONTROL_C2S),
                sendNonceBase = CONTROL_IV_S2C.copyOf(),
                receiveNonceBase = CONTROL_IV_C2S.copyOf(),
                clock = clock,
            )
            primeControl(initiator, responder)
            return initiator to responder
        }

        fun layersFor(role: SecurePeerRole): Pair<SecureRecordLayer, LanPayloadRecordLayer> {
            val controlPair = controlPair()
            val control = if (role == SecurePeerRole.INITIATOR) controlPair.first else controlPair.second
            val payload = if (role == SecurePeerRole.INITIATOR) {
                newPayloadLayer(sessionId, SecurePeerRole.INITIATOR, PAYLOAD_C2S)
            } else {
                newPayloadLayer(sessionId, SecurePeerRole.RESPONDER, PAYLOAD_S2C)
            }
            return control to payload
        }

        /** A multiplexer on the client socket whose layers this harness owns. */
        fun standaloneClient(
            isCancelled: () -> Boolean = { false },
        ): LanMultiplexedRecordChannel {
            val (controlLayer, payloadLayer) = layersFor(SecurePeerRole.INITIATOR)
            return LanMultiplexedRecordChannel(
                socket = clientSocket,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = expiresAt(),
                isCancelled = isCancelled,
            )
        }

        fun startChannels(
            isCancelled: () -> Boolean = { false },
            payloadSendAead: SecureRecordAead? = null,
        ) {
            val (initiatorControl, responderControl) = controlPair()
            val initiatorPayload = if (payloadSendAead == null) {
                newPayloadLayer(sessionId, SecurePeerRole.INITIATOR, PAYLOAD_C2S)
            } else {
                LanPayloadRecordLayer(
                    sessionId = sessionId,
                    localRole = SecurePeerRole.INITIATOR,
                    sendAead = payloadSendAead,
                    receiveAead = jcaAead(PAYLOAD_S2C),
                    sendNonceBase = PAYLOAD_IV_C2S.copyOf(),
                    receiveNonceBase = PAYLOAD_IV_S2C.copyOf(),
                    clock = clock,
                    expiresAtElapsedMillis = expiresAt(),
                )
            }
            val responderPayload = newPayloadLayer(sessionId, SecurePeerRole.RESPONDER, PAYLOAD_S2C)

            clientChannel = LanMultiplexedRecordChannel(
                socket = clientSocket,
                controlLayer = initiatorControl,
                payloadLayer = initiatorPayload,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = expiresAt(),
                isCancelled = isCancelled,
            )
            peerChannel = LanMultiplexedRecordChannel(
                socket = peerSocket,
                controlLayer = responderControl,
                payloadLayer = responderPayload,
                role = SecurePeerRole.RESPONDER,
                clock = clock,
                expiresAtElapsedMillis = expiresAt(),
                isCancelled = isCancelled,
            )
        }

        /** A multiplexer on the peer socket, for tests that must read what the client sent. */
        fun standalonePeer(
            isCancelled: () -> Boolean = { false },
        ): LanMultiplexedRecordChannel {
            val (controlLayer, payloadLayer) = layersFor(SecurePeerRole.RESPONDER)
            return LanMultiplexedRecordChannel(
                socket = peerSocket,
                controlLayer = controlLayer,
                payloadLayer = payloadLayer,
                role = SecurePeerRole.RESPONDER,
                clock = clock,
                expiresAtElapsedMillis = expiresAt(),
                isCancelled = isCancelled,
            )
        }

        /** Writes toward the peer's reader, which is the client socket's output. */
        fun writeFromClient(bytes: ByteArray) {
            clientSocket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }

        fun serverSocket(): Socket = peerSocket

        /** Writes [bytes] in pieces on a background thread so the reader sees each slice. */
        fun writeInPiecesAsync(bytes: ByteArray, cuts: List<Int>, pauseMillis: Long): Thread {
            val writer = Thread {
                val output = peerSocket.getOutputStream()
                var position = 0
                try {
                    for (cut in cuts.sorted()) {
                        if (cut <= position || cut > bytes.size) continue
                        output.write(bytes, position, cut - position)
                        output.flush()
                        position = cut
                        Thread.sleep(pauseMillis)
                    }
                    if (position < bytes.size) {
                        output.write(bytes, position, bytes.size - position)
                        output.flush()
                    }
                } catch (_: IOException) {
                    // The reader closing the socket is a legitimate end for a writer thread.
                }
            }
            writer.isDaemon = true
            writer.start()
            return writer
        }

        fun writeNow(bytes: ByteArray, length: Int = bytes.size) {
            peerSocket.getOutputStream().apply {
                write(bytes, 0, length)
                flush()
            }
        }

        fun writeRaw(bytes: ByteArray) = writeNow(bytes)

        fun closePeerSocket() {
            runCatching { peerSocket.close() }
        }

        /** The four MSR1 bytes, pinned here so the multiplexer's constant cannot drift. */
        fun msr1Magic(): ByteArray = byteArrayOf(0x4D, 0x53, 0x52, 0x31)

        private fun primeControl(
            initiator: SecureRecordLayer,
            responder: SecureRecordLayer,
        ) {
            val confirmation = ByteArray(SecureSessionLimits.KEY_CONFIRMATION_BYTES) { (it + 1).toByte() }
            val fromInitiator = initiator.protect(SecureRecordType.KEY_CONFIRMATION, confirmation)
                as SecureRecordResult.Encoded
            val fromResponder = responder.protect(SecureRecordType.KEY_CONFIRMATION, confirmation)
                as SecureRecordResult.Encoded
            check(responder.unprotect(fromInitiator.frameBytes()) is SecureRecordResult.Decoded)
            check(initiator.unprotect(fromResponder.frameBytes()) is SecureRecordResult.Decoded)
        }

        override fun close() {
            runCatching { clientChannel?.close() }
            runCatching { peerChannel?.close() }
            runCatching { clientSocket.close() }
            runCatching { peerSocket.close() }
            runCatching { listener.close() }
        }

        companion object {
            const val SESSION_HEX: String = "0123456789abcdef0123456789abcdef"
            val CONTROL_C2S: ByteArray = ByteArray(16) { (0x10 + it).toByte() }
            val CONTROL_S2C: ByteArray = ByteArray(16) { (0x30 + it).toByte() }
            val PAYLOAD_C2S: ByteArray = ByteArray(16) { (0x50 + it).toByte() }
            val PAYLOAD_S2C: ByteArray = ByteArray(16) { (0x70 + it).toByte() }
            val CONTROL_IV_C2S: ByteArray = ByteArray(12) { (0x01 + it).toByte() }
            val CONTROL_IV_S2C: ByteArray = ByteArray(12) { (0x21 + it).toByte() }
            val PAYLOAD_IV_C2S: ByteArray = ByteArray(12) { (0x41 + it).toByte() }
            val PAYLOAD_IV_S2C: ByteArray = ByteArray(12) { (0x61 + it).toByte() }
        }
    }

    // --- test doubles -----------------------------------------------------------------------------

    /** Holds a seal open so a control record can be shown queueing behind a record in progress. */
    private class GatedAead(private val delegate: SecureRecordAead) : SecureRecordAead {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

        fun release() = released.countDown()

        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
            entered.countDown()
            released.await(10, TimeUnit.SECONDS)
            return delegate.encrypt(nonce, associatedData, plaintext)
        }

        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? =
            delegate.decrypt(nonce, associatedData, ciphertext)

        override fun close() = delegate.close()
    }

    /** Counts closes so an exactly-once teardown can be asserted. */
    private class CountingAead(
        private val delegate: SecureRecordAead,
        private val counter: AtomicInteger,
    ) : SecureRecordAead {
        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray =
            delegate.encrypt(nonce, associatedData, plaintext)

        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? =
            delegate.decrypt(nonce, associatedData, ciphertext)

        override fun close() {
            counter.incrementAndGet()
            delegate.close()
        }
    }

    /** A socket whose streams the test supplies, so a write can fail, block, or be counted. */
    private class FakeSocket(private val output: OutputStream) : Socket() {
        val closeCount = AtomicInteger(0)
        private var timeout = 0
        private val empty = java.io.ByteArrayInputStream(ByteArray(0))

        override fun getInputStream(): InputStream = empty

        override fun getOutputStream(): OutputStream = output

        override fun getSoTimeout(): Int = timeout

        override fun setSoTimeout(value: Int) {
            timeout = value
        }

        override fun close() {
            closeCount.incrementAndGet()
            if (output is BlockingOutputStream) output.release()
        }
    }

    /** Accepts a bounded number of bytes, then throws the configured failure. */
    private class FailingOutputStream(
        private val writeLimit: Int,
        private val exception: IOException,
    ) : OutputStream() {
        val writeAttempts = AtomicInteger(0)
        private var accepted = 0

        override fun write(value: Int) {
            throw exception
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writeAttempts.incrementAndGet()
            if (accepted + length > writeLimit) {
                accepted = writeLimit
                throw exception
            }
            accepted += length
        }

        override fun flush() = Unit
    }

    /** Blocks the writer until the socket is closed, like a send buffer whose peer stopped reading. */
    private class BlockingOutputStream : OutputStream() {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

        fun release() = released.countDown()

        override fun write(value: Int) {
            throw IOException("closed")
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            released.await(10, TimeUnit.SECONDS)
            throw IOException("closed")
        }

        override fun flush() = Unit
    }

    /** Discards bytes and never fails; used where only the close accounting matters. */
    private class RecordingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

        override fun flush() = Unit
    }

    private companion object {
        /**
         * The JDK AES-GCM implementation stands in for Conscrypt here, exactly as the other
         * loopback tests in this module do. The device path uses Conscrypt and is exercised by the
         * API 23 test.
         */
        fun jcaAead(key: ByteArray): SecureRecordAead = object : SecureRecordAead {
            private val keyBytes = key.copyOf()

            override fun encrypt(
                nonce: ByteArray,
                associatedData: ByteArray,
                plaintext: ByteArray,
            ): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(128, nonce),
                )
                cipher.updateAAD(associatedData)
                return cipher.doFinal(plaintext)
            }

            override fun decrypt(
                nonce: ByteArray,
                associatedData: ByteArray,
                ciphertext: ByteArray,
            ): ByteArray? = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(128, nonce),
                )
                cipher.updateAAD(associatedData)
                cipher.doFinal(ciphertext)
            } catch (_: Exception) {
                null
            }

            override fun close() {
                keyBytes.fill(0)
            }
        }
    }
}
