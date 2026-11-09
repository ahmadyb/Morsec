package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureSessionLimits
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The dormant payload capability: payload keys that exist, are bound to one authenticated session,
 * and have no way to act.
 *
 * WHY A CAPABILITY AND NOT A KEY GETTER
 *   The control side learned this lesson once already. A getter can be called twice, copied into a
 *   field, or read by code that never owned the connection; a capability can be claimed exactly
 *   once and stops existing when its owner closes. This type is the whole of the payload half of
 *   that idea: it holds keyed AEADs, it can produce one record layer, and it exposes no key bytes.
 *
 * WHERE IT COMES FROM, AND WHERE IT GOES
 *   Derived inside `PakeSessionCoordinator`, at the only moment the TLS exporter and the transcript
 *   digest are both alive. Passed to `AuthenticatedControlAuthority`, which refuses to issue at all
 *   unless this material is bound to the same session, role, transcript and expiry as the rest of
 *   the evidence. Claimed -- once -- by `AuthenticatedControlAuthority.AuthenticatedControlChannel`
 *   and owned from then on by `SecureLanControlChannel`, which in this slice never claims it. No
 *   sibling authority appears beside the control authority, and nothing outside this module can
 *   name the type.
 *
 * WHAT IT CANNOT DO
 *   It does not open a socket, write a byte, read a stream, persist anything or appear in a log. It
 *   has no `toString()` that carries material, and it is not a data class, so equality is identity
 *   and a hash of it cannot be compared against another.
 *
 * CLOSING
 *   Before a claim, [close] destroys the AEADs and the nonce bases. After a claim it closes the one
 *   layer that was produced, which closes the same AEADs. Either way the capability is spent: a
 *   closed capability cannot be claimed, and a claimed one cannot be claimed again.
 *
 * STATUS
 *   Dormant. Nothing in production claims one, no payload byte crosses any transport, and the
 *   payload gate still refuses every session. Experimental, project-defined, not independently
 *   cryptographically reviewed; see doc/security/owner-risk-waiver-2026-10-19.md.
 */
internal class LanPayloadKeySchedule private constructor(
    /** The authenticated session this material belongs to. Not a secret; a correlator. */
    val sessionId: SessionId,
    /** The local role, which is what decides send from receive. */
    val localRole: SecurePeerRole,
    /** When the pairing that produced this material started. */
    val pairStartedElapsedMillis: Long,
    /** The same absolute deadline the control session carries. */
    val expiryElapsedMillis: Long,
    transcriptDigest: ByteArray,
    sender: SecureRecordAead,
    receiver: SecureRecordAead,
    sendNonceBase: ByteArray,
    receiveNonceBase: ByteArray,
    private val clock: MonotonicClock,
) : AutoCloseable {

    private val transcriptDigestValue: ByteArray = transcriptDigest.copyOf()
    private val senderValue: SecureRecordAead = sender
    private val receiverValue: SecureRecordAead = receiver
    private val sendNonceBaseValue: ByteArray = sendNonceBase.copyOf()
    private val receiveNonceBaseValue: ByteArray = receiveNonceBase.copyOf()

    private val claimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var issuedLayer: LanPayloadRecordLayer? = null

    fun isClaimed(): Boolean = claimed.get()

    fun isClosed(): Boolean = closed.get()

    /**
     * True when [digest] is the transcript this material was derived over.
     *
     * The digest is not a secret; this is a binding check, not a comparison with a timing property.
     */
    fun bindsTranscript(digest: ByteArray): Boolean = transcriptDigestValue.contentEquals(digest)

    /**
     * Produces the one payload record layer this capability will ever produce, or null.
     *
     * Null means the capability is spent: already claimed, already closed, or born of a pairing
     * that never finished. The returned layer owns the AEADs from here on; closing this capability
     * closes that layer, which is how a control channel closing can still reach payload state it
     * no longer references directly.
     */
    @Synchronized
    fun claimRecordLayer(): LanPayloadRecordLayer? {
        if (closed.get()) return null
        if (!claimed.compareAndSet(false, true)) return null
        val layer = LanPayloadRecordLayer(
            sessionId = sessionId,
            localRole = localRole,
            sendAead = senderValue,
            receiveAead = receiverValue,
            sendNonceBase = sendNonceBaseValue,
            receiveNonceBase = receiveNonceBaseValue,
            clock = clock,
            expiresAtElapsedMillis = expiryElapsedMillis,
        )
        // The layer copied both bases; the originals are wiped so only one live copy exists.
        sendNonceBaseValue.fill(0)
        receiveNonceBaseValue.fill(0)
        issuedLayer = layer
        return layer
    }

    /**
     * Releases everything this capability owns. Idempotent.
     *
     * Closing after a claim closes the issued layer. Closing before a claim destroys the AEADs
     * directly, because no layer exists to own them.
     */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val layer = issuedLayer
        if (layer != null) {
            runCatching { layer.close() }
        } else {
            runCatching { senderValue.close() }
            runCatching { receiverValue.close() }
        }
        sendNonceBaseValue.fill(0)
        receiveNonceBaseValue.fill(0)
        transcriptDigestValue.fill(0)
    }

    override fun toString(): String =
        "LanPayloadKeySchedule(role=$localRole, claimed=${claimed.get()}, closed=${closed.get()}, " +
            "ids=[redacted])"

    internal companion object {

        /**
         * Binds freshly exported payload material to one session.
         *
         * Every argument is either public metadata or an already-keyed AEAD; no raw key passes
         * through here, which is why `create` cannot leak one by accident. The AEADs become the
         * property of the returned capability, and a caller that fails after this call must [close]
         * it.
         *
         * @throws IllegalArgumentException for a non-digest transcript, a nonce base of the wrong
         *   width, or a negative pairing start.
         */
        fun create(
            sessionId: SessionId,
            localRole: SecurePeerRole,
            transcriptDigest: ByteArray,
            initiatorToResponderAead: SecureRecordAead,
            initiatorToResponderIv: ByteArray,
            responderToInitiatorAead: SecureRecordAead,
            responderToInitiatorIv: ByteArray,
            pairStartedElapsedMillis: Long,
            clock: MonotonicClock,
        ): LanPayloadKeySchedule {
            require(transcriptDigest.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
                "the payload key schedule binds a ${SecureSessionLimits.TRANSCRIPT_HASH_BYTES}-byte transcript digest"
            }
            require(initiatorToResponderIv.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) {
                "the payload nonce base is ${SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM} bytes"
            }
            require(responderToInitiatorIv.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM) {
                "the payload nonce base is ${SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM} bytes"
            }
            require(pairStartedElapsedMillis >= 0L) { "the pairing start is not negative" }
            val initiator = localRole == SecurePeerRole.INITIATOR
            return LanPayloadKeySchedule(
                sessionId = sessionId,
                localRole = localRole,
                pairStartedElapsedMillis = pairStartedElapsedMillis,
                expiryElapsedMillis = saturatingAdd(
                    pairStartedElapsedMillis,
                    SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS,
                ),
                transcriptDigest = transcriptDigest,
                sender = if (initiator) initiatorToResponderAead else responderToInitiatorAead,
                receiver = if (initiator) responderToInitiatorAead else initiatorToResponderAead,
                sendNonceBase = if (initiator) initiatorToResponderIv else responderToInitiatorIv,
                receiveNonceBase = if (initiator) responderToInitiatorIv else initiatorToResponderIv,
                clock = clock,
            )
        }

        private fun saturatingAdd(value: Long, duration: Long): Long =
            if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
    }
}
