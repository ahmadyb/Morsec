package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.SessionId

/** Capability bits on discovery and control-handshake wire messages. */
public enum class SessionFeature(public val wireBit: Int) {
    CONTROL_HANDSHAKE(1 shl 0),
    FILE_PAYLOAD(1 shl 1),
    RESUME(1 shl 2),
    SECURE_SESSION(1 shl 3);

    public companion object {
        public fun fromMask(mask: Int): Set<SessionFeature>? {
            if (mask and KNOWN_MASK.inv() != 0) return null
            return entries.filterTo(linkedSetOf()) { mask and it.wireBit != 0 }
        }

        public fun mask(features: Set<SessionFeature>): Int = features.sumOf { it.wireBit }

        public val KNOWN_MASK: Int = entries.sumOf { it.wireBit }
    }
}

/** Part A advertises NONE; TLS 1.3 is reserved for the Part B authenticated upgrade. */
public enum class EncryptionCapability(public val wireId: Int) {
    NONE(0),
    TLS_1_3(1);

    public companion object {
        public fun fromWireId(id: Int): EncryptionCapability? = entries.firstOrNull { it.wireId == id }
    }
}

/** Versioned capability advertisement; features, not chunk-size metadata, enable payload. */
public data class SessionCapabilities(
    public val supportedTransports: Set<TransportKind>,
    public val features: Set<SessionFeature>,
    /** Capacity ceiling only; FILE_PAYLOAD must also be negotiated before using it. */
    public val maxChunkSizeBytes: Int,
    public val resumeSupported: Boolean,
    public val encryption: EncryptionCapability,
) {
    init {
        require(supportedTransports.isNotEmpty()) { "at least one peer transport must be advertised" }
        require(supportedTransports.all { it == TransportKind.LAN || it == TransportKind.NEARBY }) {
            "only peer transports may be advertised"
        }
        require(SessionFeature.CONTROL_HANDSHAKE in features) { "control handshake capability is required" }
        require(maxChunkSizeBytes in 0..ProtocolLimits.MAX_CHUNK_SIZE_BYTES) {
            "maximum chunk size is outside the protocol bound"
        }
        require(SessionFeature.FILE_PAYLOAD !in features || maxChunkSizeBytes > 0) {
            "payload capability requires a non-zero maximum chunk size"
        }
        require(resumeSupported == (SessionFeature.RESUME in features)) {
            "resume capability and feature flags disagree"
        }
        require(SessionFeature.RESUME !in features || SessionFeature.FILE_PAYLOAD in features) {
            "resume capability requires file payload capability"
        }
        require(encryption == EncryptionCapability.NONE && SessionFeature.SECURE_SESSION !in features) {
            "this protocol revision has no approved secure-session capability"
        }
    }

    public companion object {
        /** Honest Part A profile: control only, no payload, no resume, and no security. */
        public fun controlOnly(transport: TransportKind): SessionCapabilities = SessionCapabilities(
            supportedTransports = setOf(transport),
            features = setOf(SessionFeature.CONTROL_HANDSHAKE),
            maxChunkSizeBytes = ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES,
            resumeSupported = false,
            encryption = EncryptionCapability.NONE,
        )
    }
}

/** Metadata asserted by a peer; it remains unauthenticated until a future secure layer. */
public data class SessionPeerProfile(
    public val peerInstanceId: PeerInstanceId,
    public val displayName: String,
    public val appVersion: String,
    public val protocolRange: ProtocolRange,
    public val capabilities: SessionCapabilities,
) {
    init {
        require(isSafeDisplayName(displayName)) { "peer display name is invalid" }
        require(isSafeAppVersion(appVersion)) { "peer app version is invalid" }
    }

    override fun toString(): String =
        "SessionPeerProfile(peerInstanceId=[redacted], appVersion=$appVersion)"
}

public sealed interface SessionHandshakeMessage {
    public val attemptId: SessionId
    public val senderPeerInstanceId: PeerInstanceId
    public val targetPeerInstanceId: PeerInstanceId
}

/** First message on a selected-peer control connection. */
public data class SessionHello(
    override val attemptId: SessionId,
    override val senderPeerInstanceId: PeerInstanceId,
    override val targetPeerInstanceId: PeerInstanceId,
    public val profile: SessionPeerProfile,
) : SessionHandshakeMessage {
    init {
        require(senderPeerInstanceId == profile.peerInstanceId) { "hello sender does not match its profile" }
    }

    override fun toString(): String = "SessionHello(attemptId=[redacted], sender=[redacted], target=[redacted])"
}

/** Responder identity plus the bounded capability intersection selected. */
public data class SessionAccept(
    override val attemptId: SessionId,
    override val senderPeerInstanceId: PeerInstanceId,
    override val targetPeerInstanceId: PeerInstanceId,
    public val profile: SessionPeerProfile,
    public val selectedProtocolVersion: Int,
    public val negotiated: NegotiatedCapabilities,
) : SessionHandshakeMessage {
    init {
        require(senderPeerInstanceId == profile.peerInstanceId) { "accept sender does not match its profile" }
        require(selectedProtocolVersion in profile.protocolRange.minimum..profile.protocolRange.maximum) {
            "selected protocol is outside the responder's range"
        }
    }

    override fun toString(): String =
        "SessionAccept(attemptId=[redacted], selectedProtocolVersion=$selectedProtocolVersion)"
}

/** Stable reject codes; no peer-supplied string is reflected in a rejection. */
public enum class HandshakeRejectCode(public val wireId: Int) {
    NO_COMMON_PROTOCOL(1),
    TARGET_ID_MISMATCH(2),
    INVALID_CAPABILITIES(3),
    POLICY_REFUSED(4);

    public companion object {
        public fun fromWireId(id: Int): HandshakeRejectCode? = entries.firstOrNull { it.wireId == id }
    }
}

public data class SessionReject(
    override val attemptId: SessionId,
    override val senderPeerInstanceId: PeerInstanceId,
    override val targetPeerInstanceId: PeerInstanceId,
    public val code: HandshakeRejectCode,
) : SessionHandshakeMessage {
    override fun toString(): String = "SessionReject(attemptId=[redacted], code=${code.name})"
}

/** Capabilities selected by both peers, never stronger than their intersection. */
public data class NegotiatedCapabilities(
    public val features: Set<SessionFeature>,
    public val maxChunkSizeBytes: Int,
    public val resumeSupported: Boolean,
    public val encryption: EncryptionCapability,
) {
    init {
        require(SessionFeature.CONTROL_HANDSHAKE in features) { "control handshake must be negotiated" }
        require(maxChunkSizeBytes in 0..ProtocolLimits.MAX_CHUNK_SIZE_BYTES) {
            "negotiated chunk size is outside the protocol bound"
        }
        require(SessionFeature.FILE_PAYLOAD !in features || maxChunkSizeBytes > 0) {
            "negotiated payload capability requires a non-zero maximum chunk size"
        }
        require(resumeSupported == (SessionFeature.RESUME in features)) {
            "negotiated resume capability and features disagree"
        }
        require(SessionFeature.RESUME !in features || SessionFeature.FILE_PAYLOAD in features) {
            "negotiated resume capability requires file payload"
        }
        val secureSession = SessionFeature.SECURE_SESSION in features
        require((encryption == EncryptionCapability.TLS_1_3) == secureSession) {
            "secure-session feature and TLS 1.3 capability must agree"
        }
        require(!secureSession || (
            SessionFeature.FILE_PAYLOAD !in features &&
                SessionFeature.RESUME !in features &&
                maxChunkSizeBytes == 0
            )
        ) { "Part B secure-control sessions cannot negotiate payload or resume" }
    }
}

/** Successful result from a validated, selected-peer capability negotiation. */
public data class NegotiatedSession(
    public val attemptId: SessionId,
    public val localProfile: SessionPeerProfile,
    public val remoteProfile: SessionPeerProfile,
    public val protocolVersion: Int,
    public val capabilities: NegotiatedCapabilities,
    public val security: SessionSecurityState = SessionSecurityState.ControlOnlyUnauthenticated,
) {
    init {
        require(protocolVersion in localProfile.protocolRange.minimum..localProfile.protocolRange.maximum) {
            "negotiated protocol is outside the local range"
        }
        require(protocolVersion in remoteProfile.protocolRange.minimum..remoteProfile.protocolRange.maximum) {
            "negotiated protocol is outside the remote range"
        }
        // A NegotiatedSession always describes the *unauthenticated* control session. There is no
        // public "authenticated session" marker on purpose: a marker is data, and data can be
        // synthesized by any caller that can name the type. Post-pairing authority lives in the
        // opaque, non-serializable secure control channel owned by :transport-lan, which can only
        // come into existence after a protected write and an authenticated peer confirmation.
        require(security === SessionSecurityState.ControlOnlyUnauthenticated) {
            "a negotiated session must carry the unauthenticated control marker"
        }
        require(
            SessionFeature.SECURE_SESSION !in capabilities.features &&
                capabilities.encryption == EncryptionCapability.NONE,
        ) { "a negotiated control session must not claim secure-session capability" }
    }

    override fun toString(): String =
        "NegotiatedSession(attemptId=[redacted], protocolVersion=$protocolVersion, security=${security.label})"
}

/** Part A uses only an unauthenticated, unencrypted control session. */
public sealed interface SessionSecurityState {
    public val label: String

    public data object ControlOnlyUnauthenticated : SessionSecurityState {
        override val label: String = "control_only_unauthenticated"
    }
}

public sealed interface SessionHandshakeDecision {
    public data class Accepted(
        public val response: SessionAccept,
        public val session: NegotiatedSession,
    ) : SessionHandshakeDecision

    public data class Rejected(
        public val response: SessionReject,
        public val failure: SessionFailure,
    ) : SessionHandshakeDecision
}

/** Pure negotiation shared by every transport. */
public object SessionHandshakeNegotiator {
    public fun accept(local: SessionPeerProfile, hello: SessionHello): SessionHandshakeDecision {
        val rejection = SessionReject(
            attemptId = hello.attemptId,
            senderPeerInstanceId = local.peerInstanceId,
            targetPeerInstanceId = hello.senderPeerInstanceId,
            code = HandshakeRejectCode.TARGET_ID_MISMATCH,
        )
        if (hello.targetPeerInstanceId != local.peerInstanceId ||
            hello.senderPeerInstanceId == local.peerInstanceId
        ) {
            return SessionHandshakeDecision.Rejected(
                rejection,
                SessionFailure(SessionFailureCode.PEER_IDENTITY_MISMATCH),
            )
        }
        val selectedVersion = local.protocolRange.highestCommon(hello.profile.protocolRange)
            ?: return SessionHandshakeDecision.Rejected(
                rejection.copy(code = HandshakeRejectCode.NO_COMMON_PROTOCOL),
                SessionFailure(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED),
            )
        val negotiated = negotiateCapabilities(local.capabilities, hello.profile.capabilities)
            ?: return SessionHandshakeDecision.Rejected(
                rejection.copy(code = HandshakeRejectCode.INVALID_CAPABILITIES),
                SessionFailure(SessionFailureCode.HANDSHAKE_INVALID),
            )
        val response = SessionAccept(
            attemptId = hello.attemptId,
            senderPeerInstanceId = local.peerInstanceId,
            targetPeerInstanceId = hello.senderPeerInstanceId,
            profile = local,
            selectedProtocolVersion = selectedVersion,
            negotiated = negotiated,
        )
        return SessionHandshakeDecision.Accepted(
            response = response,
            session = NegotiatedSession(
                attemptId = hello.attemptId,
                localProfile = local,
                remoteProfile = hello.profile,
                protocolVersion = selectedVersion,
                capabilities = negotiated,
            ),
        )
    }

    public fun verifyAccepted(
        local: SessionPeerProfile,
        expectedSelectedPeer: PeerInstanceId,
        sentHello: SessionHello,
        accept: SessionAccept,
    ): SessionHandshakeDecision {
        val rejection = SessionReject(
            attemptId = sentHello.attemptId,
            senderPeerInstanceId = local.peerInstanceId,
            targetPeerInstanceId = expectedSelectedPeer,
            code = HandshakeRejectCode.TARGET_ID_MISMATCH,
        )
        if (accept.attemptId != sentHello.attemptId ||
            accept.senderPeerInstanceId != expectedSelectedPeer ||
            accept.targetPeerInstanceId != local.peerInstanceId ||
            sentHello.senderPeerInstanceId != local.peerInstanceId ||
            sentHello.profile != local ||
            sentHello.targetPeerInstanceId != expectedSelectedPeer
        ) {
            return SessionHandshakeDecision.Rejected(
                rejection,
                SessionFailure(SessionFailureCode.PEER_IDENTITY_MISMATCH),
            )
        }
        val selected = accept.selectedProtocolVersion
        if (selected !in local.protocolRange.minimum..local.protocolRange.maximum ||
            selected !in accept.profile.protocolRange.minimum..accept.profile.protocolRange.maximum
        ) {
            return SessionHandshakeDecision.Rejected(
                rejection.copy(code = HandshakeRejectCode.NO_COMMON_PROTOCOL),
                SessionFailure(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED),
            )
        }
        val maximum = negotiateCapabilities(local.capabilities, accept.profile.capabilities)
            ?: return SessionHandshakeDecision.Rejected(
                rejection.copy(code = HandshakeRejectCode.INVALID_CAPABILITIES),
                SessionFailure(SessionFailureCode.HANDSHAKE_INVALID),
            )
        if (!isValidNegotiatedCapabilities(accept.negotiated, maximum)) {
            return SessionHandshakeDecision.Rejected(
                rejection.copy(code = HandshakeRejectCode.INVALID_CAPABILITIES),
                SessionFailure(SessionFailureCode.HANDSHAKE_INVALID),
            )
        }
        return SessionHandshakeDecision.Accepted(
            response = accept,
            session = NegotiatedSession(
                attemptId = accept.attemptId,
                localProfile = local,
                remoteProfile = accept.profile,
                protocolVersion = selected,
                capabilities = accept.negotiated,
            ),
        )
    }

    private fun negotiateCapabilities(
        local: SessionCapabilities,
        remote: SessionCapabilities,
    ): NegotiatedCapabilities? {
        val shared = local.features intersect remote.features
        if (SessionFeature.CONTROL_HANDSHAKE !in shared) return null
        // Milestone 4 Part A deliberately has no approved authenticated channel
        // or payload implementation. Never negotiate payload/resume based only
        // on peer-advertised bits, even if a future or hostile peer sets them.
        val controlOnly = shared.toMutableSet().apply {
            remove(SessionFeature.FILE_PAYLOAD)
            remove(SessionFeature.RESUME)
            remove(SessionFeature.SECURE_SESSION)
        }
        return NegotiatedCapabilities(
            features = controlOnly,
            maxChunkSizeBytes = minOf(local.maxChunkSizeBytes, remote.maxChunkSizeBytes),
            resumeSupported = false,
            encryption = EncryptionCapability.NONE,
        )
    }

    private fun isValidNegotiatedCapabilities(
        actual: NegotiatedCapabilities,
        maximum: NegotiatedCapabilities,
    ): Boolean =
        actual.features.all { it in maximum.features } &&
            actual.maxChunkSizeBytes <= maximum.maxChunkSizeBytes &&
            (!actual.resumeSupported || maximum.resumeSupported) &&
            actual.encryption == EncryptionCapability.NONE &&
            SessionFeature.SECURE_SESSION !in actual.features
}
