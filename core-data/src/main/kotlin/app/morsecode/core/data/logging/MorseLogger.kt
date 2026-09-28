package app.morsecode.core.data.logging

import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.LogLevel
import kotlinx.coroutines.flow.Flow

/**
 * Application log.
 *
 * Every subsystem logs through this interface (tags: `transfer`, `discovery`,
 * `webshare`, `storage`, `media`, `ui`). Messages are redacted before they reach
 * logcat or Room, and the Logs screen observes the same rows the Share action
 * exports, so what the user sees is exactly what is stored.
 */
public interface MorseLogger {

    public fun d(tag: String, message: String)

    public fun i(tag: String, message: String)

    public fun w(tag: String, message: String, errorId: String? = null)

    public fun e(tag: String, message: String, throwable: Throwable? = null, errorId: String? = null)

    /** Convenience for subsystems that log a failure and its reason together. */
    public fun failure(tag: String, errorId: String, message: String, throwable: Throwable? = null)

    public fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<LogEntry>>

    public fun observeProblems(limit: Int = PROBLEM_LIMIT): Flow<List<LogEntry>>

    public suspend fun clear()

    public suspend fun count(): Int

    /**
     * Full log as shareable text. Timestamps are formatted with the device
     * locale; messages were already redacted at write time.
     */
    public suspend fun export(): String

    public companion object {
        public const val DEFAULT_LIMIT: Int = 500
        public const val PROBLEM_LIMIT: Int = 200
    }
}

/** Formats a log row the way the Logs screen and the exported file show it. */
public fun LogEntry.toLine(): String {
    val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        .format(java.util.Date(timestampEpochMillis))
    return "$stamp ${level.id.padEnd(5)} ${tag.padEnd(10)} ${message}" +
        (errorId?.let { " [$it]" } ?: "")
}

/** True when the row should be highlighted in the Logs screen list. */
public val LogEntry.isProblem: Boolean get() = level == LogLevel.WARN || level == LogLevel.ERROR
