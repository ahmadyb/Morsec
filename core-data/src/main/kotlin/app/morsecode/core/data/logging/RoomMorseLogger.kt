package app.morsecode.core.data.logging

import android.util.Log
import app.morsecode.core.data.db.LogEntryEntity
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.LogLevel
import app.morsecode.core.model.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes each row to logcat immediately and to Room on the application scope.
 *
 * The database write is asynchronous on purpose: a transfer loop logs hundreds
 * of progress lines and must never block on disk. Rows that fail to persist are
 * dropped rather than retried, because a log write that throws inside the
 * engine would be worse than a missing line.
 */
@Singleton
internal class RoomMorseLogger @Inject constructor(
    private val database: MorseDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) : MorseLogger {

    private val dao get() = database.logDao()
    private val writes = AtomicInteger(0)

    override fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null, null)

    override fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message, null, null)

    override fun w(tag: String, message: String, errorId: String?) =
        write(LogLevel.WARN, tag, message, errorId, null)

    override fun e(tag: String, message: String, throwable: Throwable?, errorId: String?) =
        write(LogLevel.ERROR, tag, message, errorId, throwable)

    override fun failure(tag: String, errorId: String, message: String, throwable: Throwable?) =
        write(LogLevel.ERROR, tag, message, errorId, throwable)

    override fun observe(limit: Int): Flow<List<LogEntry>> =
        dao.observe(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeProblems(limit: Int): Flow<List<LogEntry>> =
        dao.observeProblems(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun count(): Int = dao.count()

    override suspend fun export(): String {
        val rows = dao.all()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val builder = StringBuilder()
        builder.append("Morsecode log export\n")
        builder.append("entries: ").append(rows.size).append('\n')
        builder.append("exported: ").append(stamp.format(Date())).append('\n')
        builder.append("note: tokens, secrets and private paths are redacted\n")
        builder.append("----------------------------------------\n")
        rows.forEach { row ->
            builder
                .append(stamp.format(Date(row.timestamp)))
                .append(' ')
                .append(row.level.padEnd(5))
                .append(' ')
                .append(row.tag.padEnd(10))
                .append(' ')
                .append(row.message)
            row.errorId?.let { builder.append(" [").append(it).append(']') }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun write(
        level: LogLevel,
        tag: String,
        message: String,
        errorId: String?,
        throwable: Throwable?,
    ) {
        val body = buildString {
            append(LogRedactor.redact(message))
            if (throwable != null) {
                append(" | ")
                append(LogRedactor.redactStackTrace(throwable, INLINE_TRACE_LINES))
            }
        }
        Log.println(level.logcatPriority(), LOGCAT_TAG, "$tag: $body")
        val row = LogEntryEntity(
            timestamp = System.currentTimeMillis(),
            level = level.id,
            tag = tag,
            message = body,
            errorId = errorId,
        )
        scope.launch {
            runCatching { dao.insert(row) }
            // Amortised trim keeps the table inside its cap without paying for a
            // full scan on every line.
            if (writes.incrementAndGet() % TRIM_EVERY_WRITES == 0) {
                runCatching { dao.trim(MorseDatabase.MAX_LOG_ROWS) }
            }
        }
    }

    private companion object {
        const val LOGCAT_TAG = "Morsecode"
        const val TRIM_EVERY_WRITES = 32
        const val INLINE_TRACE_LINES = 12
    }
}

private fun LogLevel.logcatPriority(): Int = when (this) {
    LogLevel.DEBUG -> Log.DEBUG
    LogLevel.INFO -> Log.INFO
    LogLevel.WARN -> Log.WARN
    LogLevel.ERROR -> Log.ERROR
}
