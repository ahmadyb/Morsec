package app.morsecode.core.model

/**
 * Feature areas that depend on a subsystem delivered by a specific milestone.
 *
 * The product forbids fake behaviour: a control must either do its stated job or
 * say plainly why it cannot yet. While the transport, transfer engine, WebShare
 * server and media playback are being built milestone by milestone, the UI asks
 * [FeatureReadiness] whether an area is live. A gated action shows an honest
 * message naming the milestone that delivers it instead of pretending to work or
 * simulating progress.
 *
 * The list is verified by tools/verify: the build fails at the final milestone
 * if anything is still gated.
 */
public enum class FeatureArea(
    public val id: String,
    /** Milestone at which this area becomes real. */
    public val deliveredInMilestone: Int,
) {
    /** Four-card tour, contextual permission requests, persisted completion. */
    ONBOARDING("onboarding", 1),

    /** Theme, accent, sounds, notifications, duplicate policy, broadcast cap. */
    SETTINGS("settings", 1),

    /** Design tokens, navigation shell and the five primary destinations. */
    DESIGN_SYSTEM("design_system", 1),

    /** Room history, logs, crash reports and DataStore preferences. */
    PERSISTENCE("persistence", 1),

    /** MediaStore / SAF / PackageManager browsing, selection, system share. */
    FILE_BROWSING("file_browsing", 1),

    /** Logs and crash reports: view, filter, export as text, clear. */
    DIAGNOSTICS("diagnostics", 1),

    /** Connection Doctor checks that need no transport. */
    DOCTOR_PLATFORM("doctor_platform", 1),

    /** Transfer descriptors, framing, checksums, resume and the queue engine. */
    TRANSFER_ENGINE("transfer_engine", 5),

    /** UDP beacon discovery and the TCP control/data transport. */
    LAN_TRANSPORT("lan_transport", 6),

    /** Google Play services Nearby Connections transport. */
    NEARBY_TRANSPORT("nearby_transport", 7),

    /** Foreground service, notification controls, process-death recovery. */
    BACKGROUND_SERVICE("background_service", 8),

    /** Duplex sessions and isolated 1→N broadcast deliveries. */
    SESSIONS_AND_BROADCAST("sessions_and_broadcast", 9),

    /** Image viewer, Media3 music and video playback. */
    MEDIA_PLAYBACK("media_playback", 10),

    /** Embedded HTTP server, local JSON API and browser session approval. */
    WEBSHARE_SERVER("webshare_server", 11),

    /** TypeScript WebShare client: browse, upload, download, range playback. */
    WEBSHARE_CLIENT("webshare_client", 12),

    /** Play services readiness check inside the Connection Doctor. */
    DOCTOR_NEARBY("doctor_nearby", 7),
}

/**
 * Progress marker for the delivery sequence in the master prompt (§16).
 *
 * Bumping [CURRENT_MILESTONE] is a deliberate act at the end of a milestone, and
 * tools/verify asserts that it has reached the final milestone before release.
 */
public object FeatureReadiness {
    public const val CURRENT_MILESTONE: Int = 1
    public const val FINAL_MILESTONE: Int = 12

    public fun isAvailable(area: FeatureArea): Boolean = area.deliveredInMilestone <= CURRENT_MILESTONE

    /** Areas that are not live yet, in delivery order. */
    public val gated: List<FeatureArea> =
        FeatureArea.entries.sortedBy { it.deliveredInMilestone }.filterNot { isAvailable(it) }

    public fun isComplete(): Boolean = gated.isEmpty() && CURRENT_MILESTONE >= FINAL_MILESTONE
}
