package app.morsecode.core.model

/** How a peer was found and how its bytes travel. */
public enum class TransportKind(
    /** Stable wire value used in the handshake and in persisted rows. */
    public val id: String,
    /** Badge text shown next to a peer, matching the approved mockup. */
    public val badge: String,
) {
    LAN("lan", "LAN"),
    NEARBY("nearby", "NEARBY"),
    WEB("web", "WEB"),
    ;

    public companion object {
        public fun fromId(id: String?): TransportKind? = entries.firstOrNull { it.id == id }
    }
}

/** Device class used for icons and for broadcast eligibility. */
public enum class DeviceKind(public val id: String) {
    PHONE("phone"),
    TABLET("tablet"),
    DESKTOP("desktop"),
    BROWSER("browser"),
    UNKNOWN("unknown"),
    ;

    public companion object {
        public fun fromId(id: String?): DeviceKind = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * A discovered peer. [endpointId] is the transport specific handle (LAN peers use
 * "host:port", Nearby peers use the Connections endpoint id, browsers use the
 * WebShare session token id).
 */
public data class Peer(
    val peerId: String,
    val displayName: String,
    val endpointId: String,
    val transport: TransportKind,
    val deviceKind: DeviceKind = DeviceKind.UNKNOWN,
    /** Single letter shown inside the avatar; derived from [displayName] if null. */
    val avatarLetter: Char? = null,
    /** Stable hue seed so a peer keeps the same avatar colour across sessions. */
    val colorSeed: String = peerId,
    /** Human readable transport detail, e.g. "192.168.1.42 · Phone · LAN". */
    val detail: String = "",
    /** App version reported in the handshake; null for browsers and old peers. */
    val appVersion: String? = null,
    /** True when the peer advertises resume support in its handshake. */
    val supportsResume: Boolean = false,
    /** True when the peer advertises an encrypted channel. */
    val supportsEncryption: Boolean = false,
    val firstSeenEpochMillis: Long = 0L,
    val lastSeenEpochMillis: Long = 0L,
    /** Signal/link quality hint from the transport, when it reports one. */
    val linkQuality: LinkQuality = LinkQuality.UNKNOWN,
) {
    val letter: Char get() = avatarLetter ?: displayName.trim().firstOrNull()?.uppercaseChar() ?: '?'

    /** Broadcasts fan out to phones and tablets only; browsers are not peers. */
    val isBroadcastEligible: Boolean get() = deviceKind == DeviceKind.PHONE || deviceKind == DeviceKind.TABLET
}

/** Coarse link quality, surfaced in the Connection Doctor and on peer rows. */
public enum class LinkQuality { UNKNOWN, WEAK, FAIR, GOOD }

/** Live discovery state machine, exposed as a Flow to the Connect destination. */
public sealed interface DiscoveryStatus {
    /** Discovery has not been started in this process. */
    public data object Idle : DiscoveryStatus

    /** Actively beaconing/advertising; [peers] updates as they appear. */
    public data class Scanning(
        val transports: Set<TransportKind>,
        val peers: List<Peer>,
        val startedEpochMillis: Long,
    ) : DiscoveryStatus

    /**
     * Discovery cannot run. [reason] is a stable id resolved to a string
     * resource by the UI, so failures are never silently swallowed.
     */
    public data class Unavailable(
        val reason: DiscoveryBlockReason,
        val transports: Set<TransportKind> = emptySet(),
        val peers: List<Peer> = emptyList(),
    ) : DiscoveryStatus

    /** Discovery stopped after an error; the message names what failed. */
    public data class Failed(val reason: DiscoveryBlockReason, val detail: String?) : DiscoveryStatus
}

/** Reasons discovery can be blocked, each with a corrective action in the UI. */
public enum class DiscoveryBlockReason(public val id: String) {
    /** Wi-Fi is off and no other transport is available. */
    NO_NETWORK("no_network"),

    /** Multicast lock could not be acquired: beacons will not arrive. */
    MULTICAST_UNAVAILABLE("multicast_unavailable"),

    /** The router appears to enforce AP/client isolation. */
    CLIENT_ISOLATION("client_isolation"),

    /** Android 12+ denied the required nearby/bluetooth runtime permission. */
    PERMISSION_MISSING("permission_missing"),

    /** Android 13+ location permission needed for Wi-Fi scanning on API 23-32. */
    LOCATION_REQUIRED_FOR_SCAN("location_required_for_scan"),

    /** Google Play services is missing, disabled or too old for Nearby. */
    PLAY_SERVICES_UNAVAILABLE("play_services_unavailable"),

    /** The control or beacon port is already bound by another process. */
    PORT_CONFLICT("port_conflict"),

    /** The user turned discovery off. */
    DISABLED_BY_USER("disabled_by_user"),
}
