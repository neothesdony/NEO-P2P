package com.neop2p.di

import android.app.Application
import android.content.Context
import com.neop2p.NeoTradeApp
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.dao.AttestationDao
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.*
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.OfferRouter
import com.neop2p.data.p2p.routing.EscrowRouter
import com.neop2p.data.p2p.store.PeerRegistry
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
        identityManager: IdentityManager,
        peerRegistry: PeerRegistry
    ): LibP2PManager = LibP2PManager(identityManager, peerRegistry)

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
    fun provideConversationKeyDao(db: AppDatabase): com.neop2p.data.local.dao.ConversationKeyDao =
        db.conversationKeyDao()

    @Provides
    @Singleton
    fun providePeerDao(db: AppDatabase): PeerDao = db.peerDao()

    @Provides
    @Singleton
    fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()

    @Provides
    @Singleton
    fun provideAttestationDao(db: AppDatabase): AttestationDao = db.attestationDao()

    @Provides
    @Singleton
    fun provideNostrClient(
        identityManager: IdentityManager,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
        db: AppDatabase
    ): NostrClient = NostrClient(identityManager, peerRegistry, db.peerDao(), db.attestationDao())

    @Provides
    @Singleton
    fun provideWebRTCManager(p2pTransport: HybridP2PTransport): WebRTCManager =
        WebRTCManager(p2pTransport)

    @Provides
    @Singleton
    fun provideMarketPriceService(
        httpClient: HttpClient
    ): com.neop2p.data.market.MarketPriceService =
        com.neop2p.data.market.MarketPriceService(httpClient)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient {
        install(io.ktor.client.plugins.HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }

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
    fun provideEscrowDao(db: AppDatabase): EscrowDao = db.escrowDao()

    @Provides
    @Singleton
    fun provideDisputeEvidenceDao(db: AppDatabase): DisputeEvidenceDao =
        db.disputeEvidenceDao()

    @Provides
    @Singleton
    fun provideChainMonitor(httpClient: HttpClient): ChainMonitor =
        ChainMonitor(httpClient)

    @Provides
    @Singleton
    fun provideWalletService(
        identityManager: IdentityManager,
        chainMonitor: ChainMonitor
    ): com.neop2p.data.wallet.WalletService =
        com.neop2p.data.wallet.WalletService(identityManager, chainMonitor)

    @Provides
    @Singleton
    fun provideEscrowService(
        db: AppDatabase,
        chainMonitor: ChainMonitor,
        identityManager: IdentityManager,
        nostrClient: NostrClient
    ): EscrowService = EscrowService(db, chainMonitor, identityManager, nostrClient)

    @Provides
    @Singleton
    fun provideOfflineQueue(pendingMessageDao: PendingMessageDao): OfflineQueue =
        OfflineQueue(pendingMessageDao)

    @Provides
    @Singleton
    fun provideChatRouter(
        signal: SignalProtocol,
        queue: OfflineQueue,
        webRTCManager: WebRTCManager,
        db: AppDatabase,
        p2pTransport: HybridP2PTransport
    ): ChatRouter = ChatRouter(signal, queue, webRTCManager, db.chatMessageDao(), db.offerDao(), p2pTransport)

    @Provides
    @Singleton
    fun provideOfferRouter(
        nostrClient: NostrClient,
        db: AppDatabase,
        deletedOfferStore: com.neop2p.data.local.DeletedOfferStore,
        identityManager: IdentityManager,
        blockedPeerStore: com.neop2p.data.local.BlockedPeerStore
    ): OfferRouter = OfferRouter(
        nostrClient, db.offerDao(), deletedOfferStore, db.peerDao(), identityManager, blockedPeerStore
    )

    @Provides
    @Singleton
    fun provideWalletWatcher(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        walletService: com.neop2p.data.wallet.WalletService,
        chainMonitor: ChainMonitor,
        notificationDispatcher: com.neop2p.service.NotificationDispatcher
    ): com.neop2p.service.WalletWatcher =
        com.neop2p.service.WalletWatcher(context, walletService, chainMonitor, notificationDispatcher)

    @Provides
    @Singleton
    fun provideP2POrchestrator(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        identityManager: IdentityManager,
        p2pTransport: HybridP2PTransport,
        signal: SignalProtocol,
        nostrClient: NostrClient,
        reputation: ReputationSystem,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
        queue: OfflineQueue,
        chatRouter: ChatRouter,
        offerRouter: OfferRouter,
        escrowRouter: EscrowRouter,
        escrowService: EscrowService,
        db: AppDatabase,
        deletedOfferStore: com.neop2p.data.local.DeletedOfferStore,
        webRTCManager: WebRTCManager,
        notificationDispatcher: com.neop2p.service.NotificationDispatcher,
        appForegroundTracker: com.neop2p.service.AppForegroundTracker,
        walletWatcher: com.neop2p.service.WalletWatcher,
        scope: CoroutineScope
    ): P2POrchestrator = P2POrchestrator(
        appContext, identityManager, p2pTransport, signal, nostrClient, reputation,
        peerRegistry, queue, chatRouter, offerRouter, escrowRouter, escrowService,
        db.offerDao(), deletedOfferStore, webRTCManager,
        notificationDispatcher, appForegroundTracker, walletWatcher, scope
    )

}