package com.neop2p.di

import android.app.Application
import android.content.Context
import com.neop2p.NeoTradeApp
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.p2p.*
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.OfferRouter
import com.neop2p.data.reputation.ReputationSystem
import io.ktor.client.HttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    @Provides
    @Singleton
    fun provideSharedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun providePendingMessageDao(db: AppDatabase): PendingMessageDao =
        db.pendingMessageDao()

    @Provides
    @Singleton
    fun provideOfflineQueue(pendingMessageDao: PendingMessageDao): OfflineQueue =
        OfflineQueue(pendingMessageDao)

    @Provides
    @Singleton
    fun provideChatRouter(
        signal: SignalProtocol,
        queue: OfflineQueue,
        db: AppDatabase
    ): ChatRouter = ChatRouter(signal, queue, db.chatMessageDao())

    @Provides
    @Singleton
    fun provideOfferRouter(
        nostrClient: NostrClient,
        db: AppDatabase
    ): OfferRouter = OfferRouter(nostrClient, db.offerDao())

    @Provides
    @Singleton
    fun provideP2POrchestrator(
        identityManager: IdentityManager,
        p2pTransport: HybridP2PTransport,
        signal: SignalProtocol,
        nostrClient: NostrClient,
        reputation: ReputationSystem,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
        queue: OfflineQueue,
        chatRouter: ChatRouter,
        offerRouter: OfferRouter,
        escrowService: EscrowService,
        scope: CoroutineScope
    ): P2POrchestrator = P2POrchestrator(
        identityManager, p2pTransport, signal, nostrClient, reputation,
        peerRegistry, queue, chatRouter, offerRouter, escrowService, scope
    )

}