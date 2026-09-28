package app.morsecode.core.data.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import app.morsecode.core.data.db.BrowserSessionDao
import app.morsecode.core.data.db.CrashReportDao
import app.morsecode.core.data.db.HistoryDao
import app.morsecode.core.data.db.LogDao
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.RecentDeviceDao
import app.morsecode.core.data.db.SafGrantDao
import app.morsecode.core.data.db.TransferItemDao
import app.morsecode.core.data.db.TransferSessionDao
import app.morsecode.core.data.db.WebTransferDao
import app.morsecode.core.model.di.ApplicationScope
import app.morsecode.core.model.di.DefaultDispatcher
import app.morsecode.core.model.di.IoDispatcher
import app.morsecode.core.model.di.MainDispatcher
import app.morsecode.core.model.di.TransferDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Database, dispatchers and the application scope.
 *
 * The scope is a supervisor on `Dispatchers.Default` so a failing log write can
 * never cancel crash recording, and it is injected (rather than created ad hoc)
 * so tests can replace it with a `TestScope`.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataModule {

    @Provides
    @Singleton
    public fun provideDatabase(@ApplicationContext context: Context): MorseDatabase =
        Room.databaseBuilder(context, MorseDatabase::class.java, MorseDatabase.NAME)
            // Queries run off the main thread by contract; failing loudly on an
            // accidental main-thread read is better than a silent jank frame.
            .setQueryCallback(
                { sqlQuery, bindArgs -> Log.v("MorsecodeSql", "$sqlQuery $bindArgs") },
                { command -> command.run() },
            )
            .build()

    @Provides
    public fun provideTransferSessionDao(database: MorseDatabase): TransferSessionDao =
        database.transferSessionDao()

    @Provides
    public fun provideTransferItemDao(database: MorseDatabase): TransferItemDao = database.transferItemDao()

    @Provides
    public fun provideHistoryDao(database: MorseDatabase): HistoryDao = database.historyDao()

    @Provides
    public fun provideRecentDeviceDao(database: MorseDatabase): RecentDeviceDao = database.recentDeviceDao()

    @Provides
    public fun provideLogDao(database: MorseDatabase): LogDao = database.logDao()

    @Provides
    public fun provideCrashReportDao(database: MorseDatabase): CrashReportDao = database.crashReportDao()

    @Provides
    public fun provideBrowserSessionDao(database: MorseDatabase): BrowserSessionDao =
        database.browserSessionDao()

    @Provides
    public fun provideSafGrantDao(database: MorseDatabase): SafGrantDao = database.safGrantDao()

    @Provides
    public fun provideWebTransferDao(database: MorseDatabase): WebTransferDao = database.webTransferDao()

    @Provides
    @Singleton
    @ApplicationScope
    public fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @IoDispatcher
    public fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    public fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @DefaultDispatcher
    public fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Dedicated pool for byte pumps. Four threads is enough to saturate a
     * Wi-Fi link while leaving the IO pool free for database and file listing
     * work, so a large transfer cannot make the UI feel stuck.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    @TransferDispatcher
    public fun provideTransferDispatcher(): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(TRANSFER_PARALLELISM)

    private const val TRANSFER_PARALLELISM = 4
}
