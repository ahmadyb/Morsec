package app.morsecode.transport.lan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.transfer.session.SecureSessionLimits
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real bundled provider, ephemeral certificate path, exporter and AES-GCM on API 23. */
@RunWith(AndroidJUnit4::class)
public class ConscryptApi23CompatibilityTest {
    @Test
    public fun bundledConscryptTls13ExporterAndAesGcmWorkOnApi23() {
        val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        var clientTls: EstablishedTlsSession? = null
        var serverTls: EstablishedTlsSession? = null
        val transcript = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 7).toByte() }
        try {
            val serverHandshake = executor.submit<EstablishedTlsSession> {
                val raw = server.accept().apply { soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS }
                ConscryptSecureSessionEngine(SecureRandom()).establish(raw, initiator = false)
            }
            val rawClient = Socket()
            rawClient.connect(server.localSocketAddress, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS)
            rawClient.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            clientTls = ConscryptSecureSessionEngine(SecureRandom()).establish(rawClient, initiator = true)
            serverTls = serverHandshake.get(15, TimeUnit.SECONDS)

            val client = requireNotNull(clientTls)
            val responder = requireNotNull(serverTls)
            assertEquals("TLSv1.3", client.protocol)
            assertEquals("TLSv1.3", responder.protocol)
            assertNotNull(client.cipherSuite)
            assertNotNull(responder.cipherSuite)

            val clientMaterial = client.export("EXPORTER-MORSEC-API23-TEST", transcript, 32)
            val serverMaterial = responder.export("EXPORTER-MORSEC-API23-TEST", transcript, 32)
            assertArrayEquals(clientMaterial, serverMaterial)
            val clientKey = clientMaterial.copyOfRange(0, SecureSessionLimits.AES_KEY_BYTES)
            val serverKey = serverMaterial.copyOfRange(0, SecureSessionLimits.AES_KEY_BYTES)
            clientMaterial.fill(0)
            serverMaterial.fill(0)
            val clientAead = client.createAead(clientKey)
            val serverAead = responder.createAead(serverKey)
            val nonce = ByteArray(SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) { (it + 11).toByte() }
            val aad = "api23-conscrypt-aead-test".toByteArray(Charsets.US_ASCII)
            val plaintext = "authenticated control record".toByteArray(Charsets.US_ASCII)
            var ciphertext: ByteArray? = null
            var decoded: ByteArray? = null
            try {
                ciphertext = clientAead.encrypt(nonce, aad, plaintext)
                decoded = serverAead.decrypt(nonce, aad, requireNotNull(ciphertext))
                assertNotNull(decoded)
                assertArrayEquals(plaintext, decoded)
                val tampered = requireNotNull(ciphertext).copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
                try {
                    assertTrue(serverAead.decrypt(nonce, aad, tampered) == null)
                } finally {
                    tampered.fill(0)
                }
            } finally {
                clientAead.close()
                serverAead.close()
                nonce.fill(0)
                aad.fill(0)
                plaintext.fill(0)
                ciphertext?.fill(0)
                decoded?.fill(0)
            }
        } finally {
            transcript.fill(0)
            clientTls?.clearFingerprints()
            serverTls?.clearFingerprints()
            try { clientTls?.socket?.close() } catch (_: Exception) { }
            try { serverTls?.socket?.close() } catch (_: Exception) { }
            try { server.close() } catch (_: Exception) { }
            executor.shutdownNow()
        }
    }
}
