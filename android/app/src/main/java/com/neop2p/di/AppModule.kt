package com.neop2p.di

import android.app.Application
import android.content.Context
import com.neop2p.NeoTradeApp
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.p2p.*
import com.neop2p.data.reputation.ReputationSystem
import io.ktor.client.HttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplication(@ApplicationContext app: Context): Application =
        app as NeoTradeApp

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // Unified: use AppDatabase.getInstance() which properly sets up SQLCipher
        // via SqlCipherPassphraseManager. No more dual-construction bug.
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideIdentityManager(
        @ApplicationContext context: Context
    ): IdentityManager = IdentityManager(context)

    @Provides
    @Singleton
    fun provideLibP2PManager(
        identityManager: IdentityManager
    ): LibP2PManager = LibP2PManager(identityManager)

    @Provides
    @Singleton
    fun provideP2PRelayTransport(
        identityManager: IdentityManager,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry
    ): P2PTransportManager = P2PTransportManager(identityManager, peerRegistry)

    @Provides
    @Singleton
    fun provideP2PTransport(
        libp2p: LibP2PManager,
        relay: P2PTransportManager,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry
    ): HybridP2PTransport = HybridP2PTransport(libp2p, relay, peerRegistry)

    @Provides
    @Singleton
    fun providePeerRegistry(): com.neop2p.data.p2p.store.PeerRegistry =
        com.neop2p.data.p2p.store.PeerRegistry()

    @Provides
    @Singleton
    fun provideSignalProtocol(
        identityManager: IdentityManager,
        p2pTransport: HybridP2PTransport,
        db: AppDatabase
    ): SignalProtocol = SignalProtocol(identityManager, p2pTransport, db)

    @Provides
    @Singleton
    fun provideOfferDao(db: AppDatabase): OfferDao = db.offerDao()

    @Provides
    @Singleton
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()

    @Provides
    @Singleton
    fun provideNostrClient(identityManager: IdentityManager): NostrClient =
        NostrClient(identityManager)

    @Provides
    @Singleton
    fun provideWebRTCManager(): WebRTCManager = WebRTCManager()

    @Provides
    @Singleton
    fun provideEscrowService(
        db: AppDatabase,
        chainMonitor: ChainMonitor
    ): EscrowService = EscrowService(db, chainMonitor)

    @Provides
    @Singleton
    fun provideChainMonitor(
        httpClient: HttpClient
    ): ChainMonitor = ChainMonitor(httpClient)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient()

    @Provides
    @Singleton
    fun provideReputationSystem(
        identityManager: IdentityManager,
        db: AppDatabase
    ): ReputationSystem = ReputationSystem(identityManager, db)

}