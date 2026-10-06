package app.morsecode.core.storage.di

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.model.di.IoDispatcher
import app.morsecode.core.storage.transfer.RoomSafCommitJournal
import app.morsecode.core.storage.transfer.SafCommitJournal
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
}
