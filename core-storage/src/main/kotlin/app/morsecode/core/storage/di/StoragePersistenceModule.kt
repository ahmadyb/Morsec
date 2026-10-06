package app.morsecode.core.storage.di

import android.content.Context
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.SafGrantDao
import app.morsecode.core.model.di.IoDispatcher
import app.morsecode.core.storage.transfer.AppPrivatePartialStore
import app.morsecode.core.storage.transfer.AppPrivateSafStaging
import app.morsecode.core.storage.transfer.ContentResolverSafPermissionReader
import app.morsecode.core.storage.transfer.RoomSafCommitGrantResolver
import app.morsecode.core.storage.transfer.RoomSafCommitJournal
import app.morsecode.core.storage.transfer.SafCommitCheckpointDiscovery
import app.morsecode.core.storage.transfer.SafCommitGrantResolver
import app.morsecode.core.storage.transfer.SafCommitJournal
import app.morsecode.core.storage.transfer.SafCommitRecoveryCoordinatorFactory
import app.morsecode.core.storage.transfer.ProductionSafCommitRecoveryCoordinatorFactory
import app.morsecode.core.storage.transfer.SafCommitRestorationCoordinator
import app.morsecode.core.storage.transfer.SafCommitRestorationPolicy
import app.morsecode.core.storage.transfer.SafStaging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

/** Room-backed persistence and explicit restoration adapters. No provider operation runs here. */
@Module
@InstallIn(SingletonComponent::class)
public object StoragePersistenceModule {

    @Provides
    @Singleton
    internal fun provideRoomSafCommitJournal(
        database: MorseDatabase,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): RoomSafCommitJournal = RoomSafCommitJournal(database, dispatcher)

    @Provides
    @Singleton
    internal fun provideSafCommitJournal(journal: RoomSafCommitJournal): SafCommitJournal = journal

    @Provides
    @Singleton
    internal fun provideSafCommitCheckpointDiscovery(
        journal: RoomSafCommitJournal,
    ): SafCommitCheckpointDiscovery = journal

    @Provides
    @Singleton
    internal fun provideSafCommitGrantResolver(
        grants: SafGrantDao,
        @ApplicationContext context: Context,
    ): SafCommitGrantResolver = RoomSafCommitGrantResolver(
        grants,
        ContentResolverSafPermissionReader(context.contentResolver),
    )

    @Provides
    @Singleton
    internal fun provideAppPrivatePartialStore(
        @ApplicationContext context: Context,
    ): AppPrivatePartialStore = AppPrivatePartialStore(File(context.filesDir, PRIVATE_PARTIAL_DIRECTORY))

    @Provides
    @Singleton
    internal fun provideSafStaging(partials: AppPrivatePartialStore): SafStaging =
        AppPrivateSafStaging(partials)

    @Provides
    @Singleton
    public fun provideSafCommitRestorationPolicy(): SafCommitRestorationPolicy =
        SafCommitRestorationPolicy()

    @Provides
    @Singleton
    internal fun provideSafCommitRecoveryCoordinatorFactory(
        @ApplicationContext context: Context,
        staging: SafStaging,
        journal: SafCommitJournal,
    ): SafCommitRecoveryCoordinatorFactory = ProductionSafCommitRecoveryCoordinatorFactory(
        resolver = context.contentResolver,
        staging = staging,
        journal = journal,
    )

    @Provides
    @Singleton
    internal fun provideSafCommitRestorationCoordinator(
        discovery: SafCommitCheckpointDiscovery,
        grantResolver: SafCommitGrantResolver,
        recoveryFactory: SafCommitRecoveryCoordinatorFactory,
        policy: SafCommitRestorationPolicy,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): SafCommitRestorationCoordinator = SafCommitRestorationCoordinator(
        discovery = discovery,
        grantResolver = grantResolver,
        recoveryFactory = recoveryFactory,
        policy = policy,
        dispatcher = dispatcher,
    )

    private const val PRIVATE_PARTIAL_DIRECTORY = "transfer-partials"
}
