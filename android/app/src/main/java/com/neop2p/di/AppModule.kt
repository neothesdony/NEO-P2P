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
import com.neop2p.service.NotificationDispatcher
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
    fun provideRnsTransport(
        @ApplicationContext context: Context,
        identityManager: IdentityManager,
        transportNodeStore: com.neop2p.data.local.TransportNodeStore
    ): RnsTransport = RnsTransport(context, identityManager, transportNodeStore)

    @Provides
    @Singleton
    fun providePeerRegistry(): com.neop2p.data.p2p.store.PeerRegistry =
        com.neop2p.data.p2p.store.PeerRegistry()

    @Provides
    @Singleton
    fun provideSignalProtocol(
        identityManager: IdentityManager,
        db: AppDatabase
    ): SignalProtocol = SignalProtocol(identityManager, db)

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
    fun provideMarketPriceService(
        httpClient: HttpClient
    ): com.neop2p.data.market.MarketPriceService =
        com.neop2p.data.market.MarketPriceService(httpClient)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        engine {
            preconfigured = okhttp3.OkHttpClient.Builder()
                .certificatePinner(com.neop2p.data.network.ExplorerPins.pinConfig())
                .build()
        }
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
    fun provideArbitratorDisputeDao(db: AppDatabase): com.neop2p.data.local.dao.ArbitratorDisputeDao =
        db.arbitratorDisputeDao()

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
        rnsTransport: RnsTransport,
        pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore
    ): EscrowService = EscrowService(db, chainMonitor, identityManager, rnsTransport, pendingDisputeStore)

    @Provides
    @Singleton
    fun provideOfflineQueue(pendingMessageDao: PendingMessageDao): OfflineQueue =
        OfflineQueue(pendingMessageDao)

    @Provides
    @Singleton
    fun provideChatRouter(
        signal: SignalProtocol,
        queue: OfflineQueue,
        db: AppDatabase,
        rnsTransport: RnsTransport
    ): ChatRouter = ChatRouter(signal, queue, db.chatMessageDao(), db.offerDao(), rnsTransport)

    @Provides
    @Singleton
    fun provideOfferRouter(
        db: AppDatabase,
        deletedOfferStore: com.neop2p.data.local.DeletedOfferStore,
        identityManager: IdentityManager,
        blockedPeerStore: com.neop2p.data.local.BlockedPeerStore,
        rnsTransport: RnsTransport,
        notificationDispatcher: NotificationDispatcher
    ): OfferRouter = OfferRouter(
        db.offerDao(), deletedOfferStore, db.peerDao(), identityManager, blockedPeerStore,
        providePeerRegistry(), rnsTransport, notificationDispatcher
    )

    @Provides
    @Singleton
    fun provideWalletWatcher(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        walletService: com.neop2p.data.wallet.WalletService,
        chainMonitor: ChainMonitor,
        notificationDispatcher: com.neop2p.service.NotificationDispatcher,
        appForegroundTracker: com.neop2p.service.AppForegroundTracker
    ): com.neop2p.service.WalletWatcher =
        com.neop2p.service.WalletWatcher(context, walletService, chainMonitor, notificationDispatcher, appForegroundTracker)

    @Provides
    @Singleton
    fun providePendingDisputeStore(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        encryptedPrefs: com.neop2p.data.local.EncryptedPrefsStore
    ): com.neop2p.data.local.PendingDisputeStore = com.neop2p.data.local.PendingDisputeStore(context, encryptedPrefs)

    @Provides
    @Singleton
    fun providePendingArbitrationStore(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        encryptedPrefs: com.neop2p.data.local.EncryptedPrefsStore
    ): com.neop2p.data.local.PendingArbitrationStore = com.neop2p.data.local.PendingArbitrationStore(context, encryptedPrefs)

    @Provides
    @Singleton
    fun provideP2POrchestrator(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        identityManager: IdentityManager,
        rnsTransport: RnsTransport,
        signal: SignalProtocol,
        reputation: ReputationSystem,
        peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
        queue: OfflineQueue,
        chatRouter: ChatRouter,
        offerRouter: OfferRouter,
        escrowRouter: EscrowRouter,
        escrowService: EscrowService,
        db: AppDatabase,
        deletedOfferStore: com.neop2p.data.local.DeletedOfferStore,
        pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
        pendingArbitrationStore: com.neop2p.data.local.PendingArbitrationStore,
        notificationDispatcher: com.neop2p.service.NotificationDispatcher,
        appForegroundTracker: com.neop2p.service.AppForegroundTracker,
        walletWatcher: com.neop2p.service.WalletWatcher,
        scope: CoroutineScope
    ): P2POrchestrator = P2POrchestrator(
        appContext, identityManager, rnsTransport, signal, reputation,
        peerRegistry, queue, chatRouter, offerRouter, escrowRouter, escrowService,
        db.offerDao(), deletedOfferStore, db.escrowDao(),
        notificationDispatcher, appForegroundTracker, walletWatcher, db.disputeEvidenceDao(), db.arbitratorDisputeDao(),
        pendingDisputeStore, pendingArbitrationStore, scope
    )

}