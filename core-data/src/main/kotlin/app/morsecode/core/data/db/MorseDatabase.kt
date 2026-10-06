package app.morsecode.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single local database.
 *
 * Version 2 is an additive extension of the immutable nine-table v1 schema.
 * KSP exports each authentic version under `core-data/schemas`; the explicit
 * [MORSE_MIGRATION_1_2] is executed by the migration test and registered in the
 * production builder. Future schema changes must add a migration and executed
 * migration test. Destructive fallbacks are forbidden because dropping a
 * confirmed offset or SAF identity can lose resumable state or make cleanup unsafe.
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
        TransferSnapshotEntity::class,
        TransferPartialEntity::class,
        SafRenameHistoryEntity::class,
        SafPendingCleanupEntity::class,
    ],
    version = 2,
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

    public abstract fun transferSnapshotDao(): TransferSnapshotDao

    public abstract fun transferPartialDao(): TransferPartialDao

    public abstract fun safRenameHistoryDao(): SafRenameHistoryDao

    public abstract fun safPendingCleanupDao(): SafPendingCleanupDao

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
