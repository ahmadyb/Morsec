package app.morsecode.core.storage.di

import android.content.Context
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.model.di.IoDispatcher
import app.morsecode.core.storage.transfer.RestorationExecutionPolicy
import app.morsecode.core.storage.transfer.RestorationMonotonicClock
import app.morsecode.core.storage.transfer.RoomSafCommitJournal
import app.morsecode.core.storage.transfer.SafCommitJournal
import app.morsecode.core.storage.transfer.SystemRestorationMonotonicClock
import app.morsecode.core.storage.transfer.TransferRestorationCoordinator
import app.morsecode.core.storage.transfer.TransferRestorationCoordinatorFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/** Room-backed persistence adapters whose contracts belong to core-storage. */
@Module
@InstallIn(SingletonComponent::class)
public object StoragePersistenceModule {

    @Provides
    @Singleton
    public fun provideSafCommitJournal(
        database: MorseDatabase,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): SafCommitJournal = RoomSafCommitJournal(database, dispatcher)

    @Provides
    @Singleton
    public fun provideRestorationExecutionPolicy(): RestorationExecutionPolicy =
        RestorationExecutionPolicy()

    @Provides
    @Singleton
    public fun provideRestorationMonotonicClock(): RestorationMonotonicClock =
        SystemRestorationMonotonicClock

    /**
     * Lazily constructed explicit-call orchestration. This provision does not
     * scan Room or access a content provider; only `restore()` performs work.
     */
    @Provides
    @Singleton
    public fun provideTransferRestorationCoordinator(
        @ApplicationContext context: Context,
        database: MorseDatabase,
        policy: RestorationExecutionPolicy,
        clock: RestorationMonotonicClock,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): TransferRestorationCoordinator = TransferRestorationCoordinatorFactory.createForProduction(
        context = context,
        database = database,
        policy = policy,
        monotonicClock = clock,
        ioDispatcher = dispatcher,
    )
}
