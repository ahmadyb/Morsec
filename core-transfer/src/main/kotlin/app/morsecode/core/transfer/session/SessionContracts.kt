package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorDetailRedactor
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.identity.SessionId

private val PEER_INSTANCE_ID_PATTERN = Regex("[0-9a-f]{32}")
private val ENDPOINT_PATTERN = Regex("[A-Za-z0-9:._-]{1,128}")

/** A process-local discovery correlation value, never a credential or trust anchor. */
public data class PeerInstanceId(public val value: String) {
    init {
        require(PEER_INSTANCE_ID_PATTERN.matches(value)) { "peer instance id is invalid" }
    }

    override fun toString(): String = "PeerInstanceId([redacted])"

    public companion object {
        public fun orNull(value: String): PeerInstanceId? =
            if (PEER_INSTANCE_ID_PATTERN.matches(value)) PeerInstanceId(value) else null
    }
}

/** Opaque transport-owned route. It must not be used as peer identity or diagnostic text. */
public data class TransportEndpoint(public val transportId: String, public val opaqueValue: String) {
    init {
        require(transportId.matches(Regex("[a-z_]{1,24}"))) { "transport id is invalid" }
        require(ENDPOINT_PATTERN.matches(opaqueValue)) { "transport endpoint is invalid" }
    }

    override fun toString(): String = "TransportEndpoint($transportId, [redacted])"
}

/** A bounded range of protocol revisions. */
public data class ProtocolRange(public val minimum: Int, public val maximum: Int) {
    init {
        require(minimum in 1..MAX_PROTOCOL_REVISION) { "minimum protocol revision is invalid" }
        require(maximum in minimum..MAX_PROTOCOL_REVISION) { "maximum protocol revision is invalid" }
    }

    public fun highestCommon(other: ProtocolRange): Int? {
        val selected = minOf(maximum, other.maximum)
        return selected.takeIf { it >= maxOf(minimum, other.minimum) }
    }

    public companion object {
        public const val MAX_PROTOCOL_REVISION: Int = 65_535
        public val CURRENT: ProtocolRange = ProtocolRange(1, 1)
    }
}

/** Untrusted metadata from one discovery transport. */
public data class PeerAdvertisement(
    public val peerInstanceId: PeerInstanceId,
    public val displayName: String,
    public val appVersion: String,
    public val transport: TransportKind,
    public val endpoint: TransportEndpoint,
    public val protocolRange: ProtocolRange,
    public val capabilities: SessionCapabilities,
) {
    init {
        require(transport == TransportKind.LAN || transport == TransportKind.NEARBY) {
            "peer transport is not a peer transport"
        }
        require(endpoint.transportId == transport.id) { "endpoint transport does not match peer transport" }
        require(isSafeDisplayName(displayName)) { "peer display name is invalid" }
        require(isSafeAppVersion(appVersion)) { "peer app version is invalid" }
        require(transport in capabilities.supportedTransports) { "peer transport is not advertised" }
    }

    override fun toString(): String =
        "PeerAdvertisement(peerInstanceId=[redacted], transport=${transport.id})"
}

/** A registry entry with monotonic, process-local observation times. */
public data class DiscoveredPeer(
    public val advertisement: PeerAdvertisement,
    public val firstSeenElapsedMillis: Long,
    public val lastSeenElapsedMillis: Long,
) {
    init {
        require(firstSeenElapsedMillis >= 0L) { "first-seen time must be non-negative" }
        require(lastSeenElapsedMillis >= firstSeenElapsedMillis) { "last-seen time precedes first-seen time" }
    }

    public val peerInstanceId: PeerInstanceId get() = advertisement.peerInstanceId
    public val isTrustAnchor: Boolean get() = false

    override fun toString(): String =
        "DiscoveredPeer(peerInstanceId=[redacted], transport=${advertisement.transport.id})"
}

/**
 * Deterministic bounded peer registry. Time is supplied by the caller; the
 * implementation reads no system clock and rejects a backward clock.
 */
public class PeerRegistry(
    private val capacity: Int = MAX_PEERS,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {
    private val peers: MutableMap<PeerInstanceId, DiscoveredPeer> = linkedMapOf()
    private var lastNowElapsedMillis: Long? = null

    init {
        require(capacity in 1..MAX_PEERS) { "peer registry capacity is outside its bound" }
        require(staleAfterMillis in 1L..MAX_STALE_AFTER_MILLIS) { "peer expiry is outside its bound" }
    }

    @Synchronized
    public fun observe(advertisement: PeerAdvertisement, nowElapsedMillis: Long): List<DiscoveredPeer> {
        expire(nowElapsedMillis)
        val previous = peers[advertisement.peerInstanceId]
        if (previous == null && peers.size >= capacity) {
            val oldest = peers.values.minWithOrNull(
                compareBy<DiscoveredPeer>({ it.lastSeenElapsedMillis }, { it.peerInstanceId.value }),
            )
            if (oldest != null) peers.remove(oldest.peerInstanceId)
        }
        peers[advertisement.peerInstanceId] = DiscoveredPeer(
            advertisement = advertisement,
            firstSeenElapsedMillis = previous?.firstSeenElapsedMillis ?: nowElapsedMillis,
            lastSeenElapsedMillis = nowElapsedMillis,
        )
        return snapshot(nowElapsedMillis)
    }

    @Synchronized
    public fun snapshot(nowElapsedMillis: Long): List<DiscoveredPeer> {
        expire(nowElapsedMillis)
        return peers.values.sortedWith(
            compareBy<DiscoveredPeer>({ it.advertisement.displayName.lowercase() }, { it.peerInstanceId.value }),
        )
    }

    @Synchronized
    public fun selected(peerInstanceId: PeerInstanceId, nowElapsedMillis: Long): DiscoveredPeer? {
        expire(nowElapsedMillis)
        return peers[peerInstanceId]
    }

    @Synchronized
    public fun clear() {
        peers.clear()
    }

    @Synchronized
    public fun size(nowElapsedMillis: Long): Int {
        expire(nowElapsedMillis)
        return peers.size
    }

    private fun expire(nowElapsedMillis: Long) {
        require(nowElapsedMillis >= 0L) { "monotonic time must be non-negative" }
        require(lastNowElapsedMillis == null || nowElapsedMillis >= requireNotNull(lastNowElapsedMillis)) {
            "monotonic time moved backwards"
        }
        lastNowElapsedMillis = nowElapsedMillis
        val expired = peers.values
            .filter { nowElapsedMillis - it.lastSeenElapsedMillis >= staleAfterMillis }
            .map { it.peerInstanceId }
        expired.forEach(peers::remove)
    }

    public companion object {
        public const val MAX_PEERS: Int = 32
        public const val MAX_STALE_AFTER_MILLIS: Long = 60_000L
        public const val DEFAULT_STALE_AFTER_MILLIS: Long = 4_800L
    }
}

/** Injected monotonic clock seam; implementations must never return wall time. */
public fun interface MonotonicClock {
    public fun nowMillis(): Long
}

/** Input to an explicitly started discovery lease. */
public data class DiscoveryRequest(
    public val displayName: String,
    public val appVersion: String,
    public val protocolRange: ProtocolRange = ProtocolRange.CURRENT,
    public val capabilities: SessionCapabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
) {
    init {
        require(isSafeDisplayName(displayName)) { "local display name is invalid" }
        require(isSafeAppVersion(appVersion)) { "local app version is invalid" }
        require(TransportKind.LAN in capabilities.supportedTransports) {
            "LAN discovery capabilities must include LAN"
        }
        require(SessionFeature.FILE_PAYLOAD !in capabilities.features &&
            SessionFeature.RESUME !in capabilities.features &&
            SessionFeature.SECURE_SESSION !in capabilities.features &&
            capabilities.encryption == EncryptionCapability.NONE
        ) {
            "Part A discovery may advertise only unauthenticated control capabilities"
        }
    }
}

/** Safe, stable failures for discovery and control negotiation. */
public enum class SessionFailureCode(
    public val id: String,
    public val safeDetail: String,
    public val retryable: Boolean,
    public val origin: ErrorOrigin,
    public val category: ErrorCategory,
) {
    INVALID_REQUEST("invalid_request", "The discovery request is invalid.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    NETWORK_UNAVAILABLE("network_unavailable", "No supported local network is available.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    MULTICAST_PERMISSION_MISSING("multicast_permission_missing", "Multicast access is not available to the app.", false, ErrorOrigin.LOCAL, ErrorCategory.PERMISSION),
    MULTICAST_LOCK_FAILED("multicast_lock_failed", "Local multicast could not be enabled.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    DISCOVERY_ALREADY_ACTIVE("discovery_already_active", "A LAN discovery lease is already active.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    DISCOVERY_PORT_CONFLICT("discovery_port_conflict", "The local discovery port is already in use.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    CONTROL_PORT_CONFLICT("control_port_conflict", "The local control port is already in use.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    NETWORK_BIND_FAILED("network_bind_failed", "The local network could not be prepared for discovery.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    BEACON_INVALID("beacon_invalid", "A discovery packet was malformed or unsupported.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    BEACON_VERSION_UNSUPPORTED("beacon_version_unsupported", "The discovery packet version is unsupported.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    HANDSHAKE_INVALID("handshake_invalid", "The control handshake was malformed.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    HANDSHAKE_VERSION_UNSUPPORTED("handshake_version_unsupported", "The control handshake version is unsupported.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    PROTOCOL_VERSION_UNSUPPORTED("protocol_version_unsupported", "The peers have no common protocol version.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    PEER_IDENTITY_MISMATCH("peer_identity_mismatch", "The selected peer identity did not match the control handshake.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    HANDSHAKE_REJECTED("handshake_rejected", "The peer declined the control handshake.", false, ErrorOrigin.REMOTE, ErrorCategory.REMOTE),
    PEER_EXPIRED("peer_expired", "The selected peer is no longer present in discovery.", true, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    DISCOVERY_LEASE_EXPIRED("discovery_lease_expired", "The discovery lease reached its maximum active duration.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    CONTROL_CONNECT_FAILED("control_connect_failed", "A control connection could not be opened.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    CONTROL_TIMEOUT("control_timeout", "The control connection timed out.", true, ErrorOrigin.UNKNOWN, ErrorCategory.TRANSPORT),
    CONTROL_CAPACITY_REACHED("control_capacity_reached", "The bounded control-session capacity is full.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    OPERATION_QUEUE_FULL("operation_queue_full", "The bounded transport work queue is full.", true, ErrorOrigin.LOCAL, ErrorCategory.TRANSPORT),
    OPERATION_CANCELLED("operation_cancelled", "The control operation was cancelled.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    SECURE_SESSION_REQUIRED("secure_session_required", "Payload transfer is refused until an approved secure session is established.", false, ErrorOrigin.LOCAL, ErrorCategory.PERMISSION),
    SECURE_SESSION_ALREADY_STARTED("secure_session_already_started", "Secure pairing has already been started for this control session.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    SECURE_SESSION_HANDSHAKE_FAILED("secure_session_handshake_failed", "The secure LAN session could not be established.", true, ErrorOrigin.UNKNOWN, ErrorCategory.TRANSPORT),
    SECURE_SESSION_TRANSCRIPT_INVALID("secure_session_transcript_invalid", "The secure-session transcript was invalid or unsupported.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    SECURE_SESSION_APPROVAL_EXPIRED("secure_session_approval_expired", "The secure-session approval request expired.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    SECURE_SESSION_APPROVAL_REJECTED("secure_session_approval_rejected", "The secure-session request was declined.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    SECURE_SESSION_CONFIRMATION_FAILED("secure_session_confirmation_failed", "Mutual secure-session key confirmation failed.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    SECURE_SESSION_RECORD_INVALID("secure_session_record_invalid", "An authenticated control record was invalid or out of sequence.", false, ErrorOrigin.REMOTE, ErrorCategory.PROTOCOL),
    SECURE_SESSION_LIMIT_REACHED("secure_session_limit_reached", "The secure session reached its bounded lifetime or record limit.", false, ErrorOrigin.LOCAL, ErrorCategory.LOCAL_ACTION),
    INTERNAL_TRANSPORT_FAILURE("internal_transport_failure", "The local transport could not complete the operation.", true, ErrorOrigin.LOCAL, ErrorCategory.UNKNOWN),
    ;
}

/** User-safe diagnostic; raw exception messages and packet fields are never retained. */
public data class SessionFailure(public val code: SessionFailureCode) {
    public val detail: String = ErrorDetailRedactor.redact(code.safeDetail)
    public val retryable: Boolean get() = code.retryable
    public val origin: ErrorOrigin get() = code.origin
    public val category: ErrorCategory get() = code.category

    override fun toString(): String = "SessionFailure(code=${code.id})"
}

public enum class DiscoveryPhase {
    STARTING,
    WAITING_FOR_NETWORK,
    SCANNING,
    STOPPED,
    FAILED,
}

/** Aggregate-only diagnostics. No packet contents, addresses, names, or exception messages. */
public data class DiscoveryDiagnostics(
    public val phase: DiscoveryPhase,
    public val peerCount: Int,
    public val beaconsAccepted: Long,
    public val beaconsRejected: Long,
    public val callbacksDropped: Long,
    public val networkChanges: Long,
    public val lastFailure: SessionFailureCode? = null,
) {
    init {
        require(peerCount >= 0 && beaconsAccepted >= 0L && beaconsRejected >= 0L)
        require(callbacksDropped >= 0L && networkChanges >= 0L)
    }
}

public interface DiscoveryListener {
    public fun onDiscoveryChanged(phase: DiscoveryPhase, peers: List<DiscoveredPeer>, diagnostics: DiscoveryDiagnostics)
    public fun onIncomingControlSession(result: ControlConnectionResult.Connected)
}

/** Construction is inert; transports start only after this method is explicitly called. */
public interface PeerDiscoveryProvider {
    public val transport: TransportKind
    public fun start(request: DiscoveryRequest, listener: DiscoveryListener): DiscoveryStartResult
}

public sealed interface DiscoveryStartResult {
    public data class Started(public val lease: DiscoveryLease) : DiscoveryStartResult
    public data class Failed(public val failure: SessionFailure) : DiscoveryStartResult
}

/** Owns discovery resources and all control sessions opened from that discovery run. */
public interface DiscoveryLease : AutoCloseable {
    public val localPeerInstanceId: PeerInstanceId
    public fun snapshot(): List<DiscoveredPeer>
    public fun diagnostics(): DiscoveryDiagnostics
    public fun connectSelected(peerInstanceId: PeerInstanceId, listener: ControlConnectionListener): CancellableOperation
    override fun close()
}

public fun interface ControlConnectionListener {
    public fun onResult(result: ControlConnectionResult)
}

public sealed interface ControlConnectionResult {
    public data class Connected(public val session: ControlSession) : ControlConnectionResult
    public data class Failed(public val failure: SessionFailure) : ControlConnectionResult
}

/** Cancelling also closes any socket owned by an in-flight attempt. */
public fun interface CancellableOperation {
    public fun cancel()
}

/** Control-only session; deliberately exposes no file or payload send API. */
public interface ControlSession : AutoCloseable {
    public val negotiated: NegotiatedSession
    override fun close()
}

/** Explicit Part B upgrade seam for one already-selected Part A LAN control session. */
public interface PairableControlSession : ControlSession {
    public fun beginSecurePairing(listener: SecurePairingListener): SecureSessionOperation
    public fun sendSecureControlRecord(type: SecureRecordType, payload: ByteArray): SecureControlSendResult
    public fun receiveSecureControlRecord(): SecureControlReceiveResult
}

/** The caller must explicitly display and decide the exact expiring approval request. */
public interface SecurePairingListener {
    public fun onApprovalRequired(request: SecurePairingApprovalRequest)
    public fun onCompleted(result: SecurePairingResult)
}

public interface SecureSessionOperation : AutoCloseable {
    public fun decide(handle: SecureApprovalHandle, decision: SecureApprovalDecision): SecureApprovalResult
    public fun cancel()
    override fun close() = cancel()
}

public sealed interface PayloadTransferDecision {
    public data class Refused(public val failure: SessionFailure) : PayloadTransferDecision

    /** Only a reviewed secure-session implementation inside :core-transfer can issue this. */
    public class Authorized internal constructor(public val evidence: ApprovedSecureSession) : PayloadTransferDecision
}

/** Part A has no secure-session factory, so its control sessions always fail closed. */
public object PayloadTransferGate {
    public fun evaluate(session: NegotiatedSession): PayloadTransferDecision =
        if (session.security is ApprovedSecureSession &&
            SessionFeature.SECURE_SESSION in session.capabilities.features &&
            SessionFeature.FILE_PAYLOAD in session.capabilities.features
        ) {
            PayloadTransferDecision.Authorized(session.security)
        } else {
            PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.SECURE_SESSION_REQUIRED))
        }
}

internal fun isSafeDisplayName(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_DISPLAY_NAME_BYTES &&
        value.none { it.code < 0x20 || it.code in 0x7F..0x9F } &&
        hasWellFormedSurrogates(value) &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES

private fun hasWellFormedSurrogates(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.code in 0xD800..0xDBFF) {
            if (index + 1 >= value.length || value[index + 1].code !in 0xDC00..0xDFFF) return false
            index += 2
        } else {
            if (character.code in 0xDC00..0xDFFF) return false
            index++
        }
    }
    return true
}

internal fun isSafeAppVersion(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_APP_VERSION_BYTES &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".+-_" } &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_APP_VERSION_BYTES

public const val MAX_DISPLAY_NAME_BYTES: Int = 96
public const val MAX_APP_VERSION_BYTES: Int = 32
