package app.morsecode.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single local database.
 *
 * Version 1 with no migrations yet; the exported schema (`core-data/schemas`,
 * produced by KSP on every build) is what makes future migrations testable with
 * `MigrationTestHelper` instead of guesswork. Any schema change must ship a
 * [androidx.room.migration.Migration] and a migration test — destructive
 * fallbacks are not used, because a lost `confirmed_bytes` offset means a
 * resumed transfer would restart from zero.
 */
@Database(
    entities = [
        TransferSessionEntity::class,
        TransferItemEntity::class,
        HistoryEntryEntity::class,
        RecentDeviceEntity::class,
        LogEntryEntity::class,
        CrashReportEntity::class,
        BrowserSessionEntity::class,
        SafGrantEntity::class,
        WebTransferEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class MorseDatabase : RoomDatabase() {

    public abstract fun transferSessionDao(): TransferSessionDao

    public abstract fun transferItemDao(): TransferItemDao

    public abstract fun historyDao(): HistoryDao

    public abstract fun recentDeviceDao(): RecentDeviceDao

    public abstract fun logDao(): LogDao

    public abstract fun crashReportDao(): CrashReportDao

    public abstract fun browserSessionDao(): BrowserSessionDao

    public abstract fun safGrantDao(): SafGrantDao

    public abstract fun webTransferDao(): WebTransferDao

    public companion object {
        public const val NAME: String = "morsecode.db"

        /** Crash reports are local only; the table is trimmed to this many rows. */
        public const val MAX_CRASH_REPORTS: Int = 50

        /** Log rows kept on device. Older rows are deleted on insert. */
        public const val MAX_LOG_ROWS: Int = 2_000

        /** Closed sessions older than this are pruned at start-up. */
        public const val SESSION_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
