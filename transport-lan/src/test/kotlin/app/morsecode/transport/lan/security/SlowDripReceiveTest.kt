package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slow-drip receive through the production [SecureLanControlChannel.receive] path.
 *
 * A peer that supplies too little data to complete one record, but often enough that no single
 * read would starve, must still be ended -- by the operation's one absolute deadline, not by
 * luck. This uses a real loopback socket and a real [SecureRecordLayer], so the bytes the channel
 * reads are a genuine valid header followed by a body that never arrives.
 *
 * One honest note about what this proves. In this channel the per-read `soTimeout` is *derived
 * from* the same absolute deadline (`readExact` sets it to `deadline - now` each iteration), so
 * there is no separate, shorter inactivity timeout that could fire first. The property under test
 * is therefore that the whole operation is bounded by one absolute deadline and terminates as a
 * control timeout -- not that two competing timeouts were raced.
 */
public class SlowDripReceiveTest {

    private class JcaAesGcmAead(key: ByteArray) : SecureRecordAead {
        private val keyBytes = key.copyOf()

        @Synchronized
        override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(plaintext)
        }

        @Synchronized
        override fun decrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray? = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        } catch (_: Exception) {
            null
        }

        @Synchronized
        override fun close() {
            keyBytes.fill(0)
        }
    }

    /** Real advancing monotonic time: a fixed clock would leave `deadline - now` constant forever. */
    private class NanoClock : MonotonicClock {
        override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
    }

    @Test
    public fun `a drip that never completes a record ends at the absolute deadline`() {
        val sessionId = SessionId("00112233445566778899aabbccddeeff")
        val clock = NanoClock()
        val listener = ServerSocket()
        listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val client = Socket()
        var peer: Socket? = null
        val terminal = CountDownLatch(1)
        var channel: SecureLanControlChannel? = null
        try {
            client.connect(listener.localSocketAddress, 5_000)
            peer = listener.accept()

            val layer = SecureRecordLayer(
                sessionId = sessionId,
                localRole = SecurePeerRole.INITIATOR,
                sendAead = JcaAesGcmAead(ByteArray(32) { 1 }),
                receiveAead = JcaAesGcmAead(ByteArray(32) { 2 }),
                sendNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 3 },
                receiveNonceBase = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { 4 },
                clock = clock,
            )
            channel = SecureLanControlChannel(
                socket = client,
                recordLayer = layer,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = Long.MAX_VALUE,
                onTerminal = { terminal.countDown() },
            )

            // A structurally valid header for the direction this channel expects, then a body that
            // never completes. PING carries no payload, so the outstanding body is the AEAD tag.
            val header = SecureRecordCodec.encodeHeader(
                sessionId = sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.PING,
                plaintextLength = 0,
            )
            val bodyOutstanding = SecureSessionLimits.AEAD_TAG_BYTES

            val started = CountDownLatch(1)
            val dripper = Thread {
                try {
                    val out = peer.getOutputStream()
                    out.write(header)
                    out.flush()
                    started.countDown()
                    // Slow enough that this many bytes cannot arrive inside the deadline, but each
                    // individual byte arrives well inside the read window that remains.
                    repeat(bodyOutstanding) {
                        out.write(byteArrayOf(0x42))
                        out.flush()
                        Thread.sleep(400L)
                    }
                } catch (_: Exception) {
                    // The channel closes the socket when the deadline fires; that is the point.
                }
            }.apply { isDaemon = true }
            dripper.start()
            assertTrue("the drip must start", started.await(5, TimeUnit.SECONDS))

            val result = channel.receive()

            assertTrue(
                "an incomplete record must never be returned, got $result",
                result is SecureControlReceiveResult.Refused,
            )
            assertEquals(
                SessionFailureCode.CONTROL_TIMEOUT,
                (result as SecureControlReceiveResult.Refused).failure.code,
            )
            assertTrue(
                "the terminal teardown must have run, releasing the channel",
                terminal.await(5, TimeUnit.SECONDS),
            )
            assertTrue("the owning socket must be closed", client.isClosed)

            // The channel is terminal: no later operation can revive it or advance a sequence.
            val again = channel.receive()
            assertTrue("a closed channel must refuse further receives", again is SecureControlReceiveResult.Refused)
            val sendResult = channel.send(SecureRecordType.PING, ByteArray(0))
            assertTrue("a closed channel must refuse further sends", sendResult is app.morsecode.core.transfer.session.SecureControlSendResult.Refused)
        } finally {
            runCatching { channel?.close() }
            runCatching { client.close() }
            runCatching { peer?.close() }
            runCatching { listener.close() }
        }
    }
}
