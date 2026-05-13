package com.neop2p.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.google.android.gms.security.ProviderInstaller
import com.neop2p.NeoTradeApp
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.*
import com.neop2p.data.reputation.ReputationSystem
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
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "neop2p.db"
        )
            // Simple encryption for v1 - SQLCipher in Phase 2
            .fallbackToDestructiveMigration()
            .build()
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
        @ApplicationContext context: Context
    ): LibP2PManager = LibP2PManager(identityManager, context)

    @Provides
    @Singleton
    fun provideSignalProtocol(
        identityManager: IdentityManager,
        libP2PManager: LibP2PManager
    ): SignalProtocol = SignalProtocol(identityManager, libP2PManager)

    @Provides
    @Singleton
    fun provideNostrClient(): NostrClient = NostrClient()

    @Provides
    @Singleton
    fun provideWebRTCManager(): WebRTCManager = WebRTCManager()

    @Provides
    @Singleton
    fun provideEscrowService(): EscrowService = EscrowService()

    @Provides
    @Singleton
    fun provideReputationSystem(): ReputationSystem = ReputationSystem()
}
