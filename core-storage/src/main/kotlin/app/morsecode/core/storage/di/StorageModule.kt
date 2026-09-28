package app.morsecode.core.storage.di

import app.morsecode.core.storage.DefaultMediaRepository
import app.morsecode.core.storage.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class StorageModule {

    @Binds
    @Singleton
    public abstract fun bindMediaRepository(impl: DefaultMediaRepository): MediaRepository
}
