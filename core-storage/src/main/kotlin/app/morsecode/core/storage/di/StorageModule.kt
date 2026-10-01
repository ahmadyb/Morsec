package app.morsecode.core.storage.di

import app.morsecode.core.storage.DefaultMediaRepository
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.media.MediaStoreDataSource
import app.morsecode.core.storage.media.MediaStoreReader
import app.morsecode.core.storage.saf.SafTreeDataSource
import app.morsecode.core.storage.saf.SafTreeReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// internal: the implementations these bind are internal, and a public member
// may not expose an internal type. Dagger generates its factories inside this
// module, so the bindings stay visible to the Hilt graph.
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StorageModule {

    @Binds
    @Singleton
    internal abstract fun bindMediaStoreDataSource(impl: MediaStoreReader): MediaStoreDataSource

    @Binds
    @Singleton
    internal abstract fun bindSafTreeDataSource(impl: SafTreeReader): SafTreeDataSource

    @Binds
    @Singleton
    internal abstract fun bindMediaRepository(impl: DefaultMediaRepository): MediaRepository
}
