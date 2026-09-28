package app.morsecode.core.model

/** Log levels shown in the Logs destination. */
public enum class LogLevel(public val id: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    ;

    public companion object {
        public fun fromId(id: String?): LogLevel = entries.firstOrNull { it.id == id } ?: INFO
    }
}

/**
 * One persisted log row. Message text is already redacted by the logger:
 * session tokens, bearer secrets and absolute private paths never reach here.
 */
public data class LogEntry(
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val level: LogLevel,
    /** Subsystem tag, e.g. "transfer", "webshare", "discovery". */
    val tag: String,
    val message: String,
    /** Optional stable error id for machine readable filtering. */
    val errorId: String? = null,
)

/** A locally stored crash report. Never uploaded (master prompt §10). */
public data class CrashReport(
    val id: Long = 0L,
    val occurredEpochMillis: Long,
    /** Component that owned the failing thread, e.g. "TransferService". */
    val component: String,
    val exceptionType: String,
    val message: String?,
    /** Redacted stack trace text. */
    val stackTrace: String,
    val appVersion: String,
    val versionCode: Int,
    val androidSdkInt: Int,
    val deviceModel: String,
    /** What the engine did with in-flight work, when it could be determined. */
    val recoveryNote: String? = null,
)

/** Outcome of one Connection Doctor check. */
public enum class CheckStatus(public val id: String) {
    OK("ok"),
    WARN("warn"),
    ERROR("error"),
    /** The check could not run at all, e.g. Play services missing. */
    UNKNOWN("unknown"),
    ;

    public val isProblem: Boolean get() = this == WARN || this == ERROR

    public companion object {
        public fun fromId(id: String?): CheckStatus = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * A single diagnostic check.
 *
 * [titleId], [detailId] and [actionId] are string resource ids so every failed
 * check can carry a translated corrective action (master prompt §10, §11).
 */
public data class DiagnosticCheck(
    val id: String,
    val titleId: Int,
    val detailId: Int,
    val status: CheckStatus,
    /** Optional formatted arguments for the detail string. */
    val detailArgs: List<String> = emptyList(),
    /** Present only when the user can fix something from this screen. */
    val actionId: Int? = null,
    val action: DiagnosticAction? = null,
)

/** Corrective action a check can offer. */
public enum class DiagnosticAction(public val id: String) {
    OPEN_WIFI_SETTINGS("open_wifi_settings"),
    REQUEST_BATTERY_EXEMPTION("request_battery_exemption"),
    REQUEST_NOTIFICATION_PERMISSION("request_notification_permission"),
    REQUEST_NEARBY_PERMISSIONS("request_nearby_permissions"),
    GRANT_STORAGE_ACCESS("grant_storage_access"),
    CHANGE_WEBSHARE_PORT("change_webshare_port"),
    RETRY_DISCOVERY("retry_discovery"),
    OPEN_PLAY_STORE("open_play_store"),
    ;

    public companion object {
        public fun fromId(id: String?): DiagnosticAction? = entries.firstOrNull { it.id == id }
    }
}
