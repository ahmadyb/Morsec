package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.SecurePairingHelloV2Codec
import app.morsecode.core.transfer.session.SecurePairingTranscript
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureSessionLimits
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.conscrypt.Conscrypt
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** A per-pairing, explicitly selected Conscrypt provider. Never installed globally. */
internal class ConscryptSecureSessionEngine(
    private val random: SecureRandom,
    /**
     * Production passes nothing and therefore gets [ConscryptPairingCryptoFactory]. The parameter
     * exists so a test can count the five cryptographic operations directly instead of inferring
     * their absence from socket activity.
     */
    private val cryptoFactory: PairingCryptoFactory = ConscryptPairingCryptoFactory(random),
) {
    private val provider: Provider = cryptoFactory.securityProvider

    fun establish(rawSocket: Socket, initiator: Boolean): EstablishedTlsSession {
        val keyPair = cryptoFactory.generateEphemeralKeyPair()
        val certificate = cryptoFactory.constructEphemeralCertificate(keyPair)
        val context = cryptoFactory.createTlsContext(keyPair, certificate)
        val identity = EphemeralTlsIdentity(keyPair, certificate)
        val host = rawSocket.inetAddress?.hostAddress ?: throw SecureSessionCryptoException()
        val sslSocket = context.socketFactory.createSocket(rawSocket, host, rawSocket.port, true) as? SSLSocket
            ?: throw SecureSessionCryptoException()
        try {
            sslSocket.useClientMode = initiator
            sslSocket.enabledProtocols = arrayOf(SecurePairingTranscript.TLS_1_3)
            sslSocket.enableSessionCreation = true
            Conscrypt.setUseSessionTickets(sslSocket, false)
            if (!initiator) sslSocket.needClientAuth = true
            cryptoFactory.startHandshake(sslSocket)
            if (!Conscrypt.isConscrypt(sslSocket) ||
                sslSocket.session.protocol != SecurePairingTranscript.TLS_1_3 ||
                sslSocket.session.cipherSuite !in SecurePairingTranscript.TLS_1_3_CIPHER_SUITES
            ) throw SecureSessionCryptoException()

            val peerChain = sslSocket.session.peerCertificates
            if (peerChain.size != 1) throw SecureSessionCryptoException()
            val peerCertificate = peerChain[0] as? X509Certificate ?: throw SecureSessionCryptoException()
            val localFingerprint = fingerprint(identity.certificate)
            val peerFingerprint = fingerprint(peerCertificate)
            try {
                return EstablishedTlsSession(
                    socket = sslSocket,
                    protocol = sslSocket.session.protocol,
                    cipherSuite = sslSocket.session.cipherSuite,
                    localCertificateFingerprint = localFingerprint,
                    peerCertificateFingerprint = peerFingerprint,
                    provider = provider,
                )
            } finally {
                localFingerprint.fill(0)
                peerFingerprint.fill(0)
            }
        } catch (failure: Exception) {
            try {
                sslSocket.close()
            } catch (_: Exception) {
                // Close is best-effort; the caller reports only a typed failure.
            }
            if (failure is SecureSessionCryptoException) throw failure
            throw SecureSessionCryptoException()
        }
    }

    private fun fingerprint(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256", provider).digest(certificate.encoded)
}

/**
 * Certificate construction, lifted out of [EphemeralTlsIdentity.create] so that it is an
 * individually observable operation rather than something buried inside a TLS helper.
 *
 * The bytes are unchanged: same subject, same 20-byte random serial with the top bit cleared and
 * the zero-serial guard, same validity window, same critical basicConstraints/keyUsage extensions,
 * same SHA256withECDSA self-signature, same post-construction signature verification. Only the call
 * boundary moved.
 */
private fun buildEphemeralCertificate(
    pair: KeyPair,
    provider: Provider,
    random: SecureRandom,
): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date((now - CERTIFICATE_CLOCK_SKEW_MILLIS).coerceAtLeast(0L))
        val notAfter = Date(if (now > Long.MAX_VALUE - CERTIFICATE_VALIDITY_MILLIS) {
            Long.MAX_VALUE
        } else {
            now + CERTIFICATE_VALIDITY_MILLIS
        })
        val serialBytes = ByteArray(20)
        val serial = try {
            random.nextBytes(serialBytes)
            serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte()
            BigInteger(1, serialBytes).let { if (it.signum() == 0) BigInteger.ONE else it }
        } finally {
            serialBytes.fill(0)
        }

        val name = X500Name("CN=Morsec Ephemeral LAN Session")
        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            pair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(provider)
            .build(pair.private)
        val certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))
        certificate.checkValidity()
        verifyCertificateSignature(certificate, pair.public, provider)
    return certificate
}

/**
 * The one boundary through which a pairing attempt performs cryptographic work.
 *
 * WHY THIS EXISTS. Ordering tests used to infer "no cryptography ran" from the absence of socket
 * stream acquisitions. That inference is unsound: ephemeral key generation, certificate
 * construction and TLS context creation are pure CPU, entropy and allocation work that never
 * touches a socket. A limiter refusal therefore needs to be shown to precede those operations by
 * counting the operations themselves. This interface is that counter's hook.
 *
 * WHAT IT IS NOT. It is not a pluggable cryptography SPI. Production constructs exactly one
 * implementation, [ConscryptPairingCryptoFactory], as a constructor default, and that
 * implementation is the only one that can produce a usable [EstablishedTlsSession] -- it is the
 * code path that calls `Conscrypt.newProvider()` and the code path that throws on any socket for
 * which `Conscrypt.isConscrypt` is false. A test implementation can observe and count, and can
 * return objects that fail later; it cannot substitute a weaker TLS stack into production, because
 * nothing in `main` ever names any other implementation.
 *
 * The five operations, in the order a granted attempt performs them:
 *  1. [generateEphemeralKeyPair]      -- ephemeral key generation
 *  2. [constructEphemeralCertificate] -- certificate construction
 *  3. [createTlsContext]              -- TLS engine / session creation
 *  4. [startHandshake]                -- TLS handshake start
 *  5. [encodePairingHello]            -- MPS2 hello construction and canonical encoding
 */
internal interface PairingCryptoFactory {
    /** The provider every operation in this factory is bound to. One instance per pairing. */
    val securityProvider: Provider

    /** 1. Ephemeral key generation. No socket I/O. */
    fun generateEphemeralKeyPair(): KeyPair

    /** 2. Self-signed ephemeral certificate construction. No socket I/O. */
    fun constructEphemeralCertificate(keyPair: KeyPair): X509Certificate

    /** 3. TLS engine creation, bound to that ephemeral identity. No socket I/O. */
    fun createTlsContext(keyPair: KeyPair, certificate: X509Certificate): SSLContext

    /** 4. TLS handshake start. This is the first operation that necessarily performs socket I/O. */
    fun startHandshake(sslSocket: SSLSocket)

    /** 5. MPS2 hello construction and canonical encoding. */
    fun encodePairingHello(offer: PairingOfferV2): ByteArray
}

/**
 * The production implementation, and the only one `main` sources reference.
 *
 * The Conscrypt requirement is enforced here exactly as before: the provider is created per
 * factory, i.e. per pairing attempt, and is never installed globally.
 */
internal class ConscryptPairingCryptoFactory(
    private val random: SecureRandom,
) : PairingCryptoFactory {
    override val securityProvider: Provider = Conscrypt.newProvider()

    override fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", securityProvider)
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return generator.generateKeyPair()
    }

    override fun constructEphemeralCertificate(keyPair: KeyPair): X509Certificate =
        buildEphemeralCertificate(keyPair, securityProvider, random)

    override fun createTlsContext(keyPair: KeyPair, certificate: X509Certificate): SSLContext {
        val context = SSLContext.getInstance(SecurePairingTranscript.TLS_1_3, securityProvider)
        context.init(
            arrayOf<KeyManager>(SingleIdentityKeyManager(EphemeralTlsIdentity(keyPair, certificate))),
            arrayOf<TrustManager>(EphemeralSelfSignedTrustManager(securityProvider)),
            random,
        )
        return context
    }

    override fun startHandshake(sslSocket: SSLSocket) {
        sslSocket.startHandshake()
    }

    override fun encodePairingHello(offer: PairingOfferV2): ByteArray =
        SecurePairingHelloV2Codec.encodeFrame(offer)
}

/** TLS metadata and exporter primitive. No exporter material is retained in the result. */
internal class EstablishedTlsSession(
    val socket: SSLSocket,
    val protocol: String,
    val cipherSuite: String,
    localCertificateFingerprint: ByteArray,
    peerCertificateFingerprint: ByteArray,
    val provider: Provider,
) {
    private val localFingerprint: ByteArray = localCertificateFingerprint.copyOf()
    private val peerFingerprint: ByteArray = peerCertificateFingerprint.copyOf()

    fun localFingerprintBytes(): ByteArray = localFingerprint.copyOf()
    fun peerFingerprintBytes(): ByteArray = peerFingerprint.copyOf()

    fun export(label: String, context: ByteArray, length: Int): ByteArray {
        require(label.isNotBlank() && label.all { it.code in 0x21..0x7E })
        require(context.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES)
        require(length in 1..64)
        if (!Conscrypt.isConscrypt(socket)) throw SecureSessionCryptoException()
        val contextCopy = context.copyOf()
        val output = try {
            Conscrypt.exportKeyingMaterial(socket, label, contextCopy, length)
        } finally {
            contextCopy.fill(0)
        }
        if (output.size != length) {
            output.fill(0)
            throw SecureSessionCryptoException()
        }
        return output
    }

    fun createAead(exportedKey: ByteArray): SecureRecordAead {
        if (exportedKey.size != SecureSessionLimits.AES_KEY_BYTES) {
            exportedKey.fill(0)
            throw SecureSessionCryptoException()
        }
        return try {
            ConscryptAesGcmAead(exportedKey, provider)
        } finally {
            exportedKey.fill(0)
        }
    }

    fun clearFingerprints() {
        localFingerprint.fill(0)
        peerFingerprint.fill(0)
    }
}

private data class EphemeralTlsIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
) {
}

private class SingleIdentityKeyManager(identity: EphemeralTlsIdentity) : X509KeyManager {
    private val privateKey: PrivateKey = identity.keyPair.private
    private val chain: Array<X509Certificate> = arrayOf(identity.certificate)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supportsEc(keyType)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (keyType?.any(::supportsEc) == true) ALIAS else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supportsEc(keyType)) arrayOf(ALIAS) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (supportsEc(keyType)) ALIAS else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) chain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null

    private fun supportsEc(keyType: String?): Boolean =
        keyType != null && (keyType.equals("EC", ignoreCase = true) || keyType.contains("ECDSA", ignoreCase = true))

    private companion object {
        const val ALIAS: String = "morsec-ephemeral-session"
    }
}

private class EphemeralSelfSignedTrustManager(private val provider: Provider) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = validate(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = validate(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun validate(chain: Array<out X509Certificate>?) {
        if (chain == null || chain.size != 1) throw CertificateException()
        val certificate = chain[0]
        if (certificate.subjectX500Principal != certificate.issuerX500Principal ||
            certificate.basicConstraints >= 0 ||
            !certificate.sigAlgName.equals("SHA256withECDSA", ignoreCase = true) ||
            certificate.keyUsage?.getOrNull(0) != true
        ) throw CertificateException()
        val publicKey = certificate.publicKey as? ECPublicKey ?: throw CertificateException()
        if (publicKey.params.curve.field.fieldSize != 256 || publicKey.params.order.bitLength() != 256) {
            throw CertificateException()
        }
        certificate.checkValidity()
        verifyCertificateSignature(certificate, publicKey, provider)
    }
}

/** Uses Bouncy Castle's JCA bridge so API 23 need not call the API-24 Provider overload. */
private fun verifyCertificateSignature(
    certificate: X509Certificate,
    publicKey: java.security.PublicKey,
    provider: Provider,
) {
    val holder = JcaX509CertificateHolder(certificate)
    val verifier = JcaContentVerifierProviderBuilder()
        .setProvider(provider)
        .build(publicKey)
    if (!holder.isSignatureValid(verifier)) throw CertificateException()
}

private class ConscryptAesGcmAead(key: ByteArray, private val provider: Provider) : SecureRecordAead {
    private val keyBytes: ByteArray = key.copyOf()
    @Volatile
    private var destroyed: Boolean = false

    override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, nonce, associatedData, plaintext)

    override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? =
        try {
            crypt(Cipher.DECRYPT_MODE, nonce, associatedData, ciphertext)
        } catch (_: Exception) {
            null
        }

    @Synchronized
    override fun close() {
        if (destroyed) return
        destroyed = true
        keyBytes.fill(0)
    }

    @Synchronized
    private fun crypt(mode: Int, nonce: ByteArray, associatedData: ByteArray, input: ByteArray): ByteArray {
        if (destroyed || nonce.size != SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) {
            throw SecureSessionCryptoException()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding", provider)
            cipher.init(
                mode,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(associatedData)
            return cipher.doFinal(input)
        } catch (_: Exception) {
            throw SecureSessionCryptoException()
        }
    }
}

internal class SecureSessionCryptoException : Exception("Secure cryptographic operation failed.")

private const val CERTIFICATE_CLOCK_SKEW_MILLIS: Long = 86_400_000L
private const val CERTIFICATE_VALIDITY_MILLIS: Long = 172_800_000L
