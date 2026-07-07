package com.neop2p.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.neop2p.NeoTradeApp
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.SqlCipherPassphraseManager
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.dao.*
import com.neop2p.data.p2p.*
import com.neop2p.data.reputation.ReputationSystem
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
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
        @ApplicationContext context: Context
    ): LibP2PManager = LibP2PManager(identityManager, context)

    @Provides
    @Singleton
    fun provideSignalProtocol(
        identityManager: IdentityManager,
        libP2PManager: LibP2PManager,
        db: AppDatabase
    ): SignalProtocol = SignalProtocol(identityManager, libP2PManager, db)

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
    fun provideEscrowService(db: AppDatabase): EscrowService = EscrowService(db)

    @Provides
    @Singleton
    fun provideReputationSystem(
        identityManager: IdentityManager,
        db: AppDatabase
    ): ReputationSystem = ReputationSystem(identityManager, db)
}