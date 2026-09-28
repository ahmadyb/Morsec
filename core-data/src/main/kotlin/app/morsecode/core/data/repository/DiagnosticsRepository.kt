package app.morsecode.core.data.repository

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.data.logging.MorseLogger
import app.morsecode.core.model.CrashReport
import app.morsecode.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read side of the Logs and Crashes destinations (master prompt §10).
 *
 * Writing goes through [MorseLogger]; this repository only exposes what the two
 * diagnostic screens list, filter, share and clear.
 */
public interface DiagnosticsRepository {

    public fun observeLogs(limit: Int = MorseLogger.DEFAULT_LIMIT): Flow<List<LogEntry>>

    public fun observeProblems(limit: Int = MorseLogger.PROBLEM_LIMIT): Flow<List<LogEntry>>

    public suspend fun clearLogs()

    public suspend fun logCount(): Int

    /** Plain text log dump for the Logs screen's Share action. */
    public suspend fun exportLogs(): String

    public fun observeCrashReports(): Flow<List<CrashReport>>

    public fun observeCrashCount(): Flow<Int>

    public suspend fun deleteCrashReport(id: Long)

    public suspend fun clearCrashReports()

    public suspend fun exportCrashReports(): String
}

@Singleton
internal class RoomDiagnosticsRepository @Inject constructor(
    private val database: MorseDatabase,
    private val logger: MorseLogger,
) : DiagnosticsRepository {

    private val crashDao get() = database.crashReportDao()

    override fun observeLogs(limit: Int): Flow<List<LogEntry>> = logger.observe(limit)

    override fun observeProblems(limit: Int): Flow<List<LogEntry>> = logger.observeProblems(limit)

    override suspend fun clearLogs() {
        logger.clear()
    }

    override suspend fun logCount(): Int = logger.count()

    override suspend fun exportLogs(): String = logger.export()

    override fun observeCrashReports(): Flow<List<CrashReport>> =
        crashDao.observe().map { rows -> rows.map { it.toDomain() } }

    override fun observeCrashCount(): Flow<Int> = crashDao.observeCount()

    override suspend fun deleteCrashReport(id: Long) {
        crashDao.delete(id)
    }

    override suspend fun clearCrashReports() {
        crashDao.clear()
    }

    override suspend fun exportCrashReports(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val reports = crashDao.all()
        val builder = StringBuilder()
        builder.append("Morsecode crash reports (stored locally, never uploaded)\n")
        builder.append("count: ").append(reports.size).append('\n')
        builder.append("========================================\n")
        reports.forEach { report ->
            builder.append('\n')
            builder.append(stamp.format(java.util.Date(report.occurredEpochMillis))).append('\n')
            builder.append("component: ").append(report.component).append('\n')
            builder.append("app: ").append(report.appVersion).append(" (").append(report.versionCode).append(")\n")
            builder.append("android: API ").append(report.androidSdkInt).append('\n')
            builder.append("device: ").append(report.deviceModel).append('\n')
            report.recoveryNote?.let { builder.append("recovery: ").append(it).append('\n') }
            builder.append(report.stackTrace).append('\n')
            builder.append("----------------------------------------\n")
        }
        return builder.toString()
    }
}
