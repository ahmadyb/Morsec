package app.morsecode.core.data.di

import app.morsecode.core.data.logging.MorseLogger
import app.morsecode.core.data.logging.RoomMorseLogger
import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.data.repository.DiagnosticsRepository
import app.morsecode.core.data.repository.HistoryRepository
import app.morsecode.core.data.repository.RoomDeviceRepository
import app.morsecode.core.data.repository.RoomDiagnosticsRepository
import app.morsecode.core.data.repository.RoomHistoryRepository
import app.morsecode.core.data.repository.RoomTransferRepository
import app.morsecode.core.data.repository.RoomWebShareRepository
import app.morsecode.core.data.repository.TransferRepository
import app.morsecode.core.data.repository.WebShareRepository
import app.morsecode.core.data.settings.DataStoreSettingsRepository
import app.morsecode.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Room/DataStore implementations to the ports the app depends on. */
@Module
@InstallIn(SingletonComponent::class)
public abstract class RepositoryModule {

    @Binds
    @Singleton
    public abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    public abstract fun bindTransferRepository(impl: RoomTransferRepository): TransferRepository

    @Binds
    @Singleton
    public abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds
    @Singleton
    public abstract fun bindDeviceRepository(impl: RoomDeviceRepository): DeviceRepository

    @Binds
    @Singleton
    public abstract fun bindDiagnosticsRepository(impl: RoomDiagnosticsRepository): DiagnosticsRepository

    @Binds
    @Singleton
    public abstract fun bindWebShareRepository(impl: RoomWebShareRepository): WebShareRepository

    @Binds
    @Singleton
    public abstract fun bindLogger(impl: RoomMorseLogger): MorseLogger

}

/*
 * CrashRecorder is a concrete @Singleton with an @Inject constructor, so Hilt
 * builds it without a binding here; the Application calls install() in onCreate.
 */
