package app.morsecode.core.model

/**
 * The five approved accent colours. The hex values are the mockup's own
 * (`ACCENTS` plus the per-accent `--acc2` / `--accInk` overrides) and are
 * verified against the reference document by tools/verify.
 */
public enum class Accent(
    public val id: String,
    /** Primary accent, the CSS custom property `--acc`. */
    public val accentArgb: Long,
    /** Darker companion, `--acc2`, used for gradients and pressed states. */
    public val accentDarkArgb: Long,
    /** Ink drawn on top of the accent, `--accInk`. */
    public val onAccentArgb: Long,
) {
    SUNFLOWER("sunflower", 0xFFFACC15, 0xFFEAB308, 0xFF1A1400),
    LEAF("leaf", 0xFF84CC16, 0xFF65A30D, 0xFF1A1400),
    EMBER("ember", 0xFFEA580C, 0xFFC2410C, 0xFFFFFFFF),
    VIOLET("violet", 0xFF8B5CF6, 0xFF7C3AED, 0xFFFFFFFF),
    SKY("sky", 0xFF0EA5E9, 0xFF0284C7, 0xFF1A1400),
    ;

    public companion object {
        public val ordered: List<Accent> = entries.toList()
        public val default: Accent = SUNFLOWER
        public fun fromId(id: String?): Accent = entries.firstOrNull { it.id == id } ?: default
    }
}

/** Theme selection. [FOLLOW_SYSTEM] defers to the platform dark-mode setting. */
public enum class ThemeMode(public val id: String) {
    DARK("dark"),
    LIGHT("light"),
    FOLLOW_SYSTEM("follow_system"),
    ;

    public companion object {
        public fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
    }
}

/**
 * Duplicate policy, applied consistently to peer transfers, browser uploads and
 * folder extraction (master prompt §7).
 */
public enum class DuplicatePolicy(public val id: String) {
    RENAME("rename"),
    OVERWRITE("overwrite"),
    SKIP("skip"),
    ASK("ask"),
    ;

    /** The order the Settings row cycles through, matching the mockup. */
    public fun next(): DuplicatePolicy {
        val all = entries
        return all[(all.indexOf(this) + 1) % all.size]
    }

    public companion object {
        public val ordered: List<DuplicatePolicy> = entries.toList()
        public val default: DuplicatePolicy = RENAME
        public fun fromId(id: String?): DuplicatePolicy = entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * Persisted user settings. Every field maps to a control that exists in the
 * approved Settings screen; nothing here is speculative.
 */
public data class MorseSettings(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val accent: Accent = Accent.default,
    val soundsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.default,
    /** Broadcast fan-out cap; the mockup cycles 2..8. */
    val broadcastPeerLimit: Int = 4,
    /** Device display name advertised in the handshake. */
    val deviceName: String? = null,
    val crashReportingEnabled: Boolean = true,
    /** Set once the four-card onboarding tour has been completed or skipped. */
    val onboardingCompleted: Boolean = false,
    val webShareEnabled: Boolean = false,
    val webSharePort: Int = NetworkPorts.WEBSHARE_HTTP,
    /** Reduced-motion respect, mirrored from the platform animator scale. */
    val reducedMotion: Boolean = false,
) {
    init {
        require(broadcastPeerLimit in BROADCAST_LIMIT_RANGE) {
            "broadcastPeerLimit must be within $BROADCAST_LIMIT_RANGE"
        }
        require(webSharePort in NetworkPorts.USER_PORT_RANGE) {
            "webSharePort must be within ${NetworkPorts.USER_PORT_RANGE}"
        }
    }

    public fun withNextBroadcastLimit(): MorseSettings {
        val next = if (broadcastPeerLimit >= BROADCAST_LIMIT_RANGE.last) {
            BROADCAST_LIMIT_RANGE.first
        } else {
            broadcastPeerLimit + 1
        }
        return copy(broadcastPeerLimit = next)
    }

    public companion object {
        /** Inclusive range offered by the Settings row: 2, 3, ... 8 phones. */
        public val BROADCAST_LIMIT_RANGE: IntRange = 2..8
    }
}
