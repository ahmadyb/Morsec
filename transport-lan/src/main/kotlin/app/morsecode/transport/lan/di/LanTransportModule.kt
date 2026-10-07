package app.morsecode.transport.lan.di

import android.content.Context
import app.morsecode.core.transfer.session.PeerDiscoveryProvider
import app.morsecode.transport.lan.LanPeerDiscoveryProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Production binding; obtaining it allocates no sockets or Android locks. */
@Module
@InstallIn(SingletonComponent::class)
public object LanTransportModule {
    @Provides
    @Singleton
    public fun provideLanPeerDiscoveryProvider(
        @ApplicationContext context: Context,
    ): PeerDiscoveryProvider = LanPeerDiscoveryProvider(context)
}
