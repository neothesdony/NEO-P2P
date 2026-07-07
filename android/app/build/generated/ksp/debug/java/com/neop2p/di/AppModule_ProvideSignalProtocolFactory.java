package com.neop2p.di;

import com.neop2p.data.local.AppDatabase;
import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
import com.neop2p.data.p2p.SignalProtocol;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class AppModule_ProvideSignalProtocolFactory implements Factory<SignalProtocol> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideSignalProtocolFactory(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<AppDatabase> dbProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.dbProvider = dbProvider;
  }

  @Override
  public SignalProtocol get() {
    return provideSignalProtocol(identityManagerProvider.get(), libP2PManagerProvider.get(), dbProvider.get());
  }

  public static AppModule_ProvideSignalProtocolFactory create(
      Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideSignalProtocolFactory(identityManagerProvider, libP2PManagerProvider, dbProvider);
  }

  public static SignalProtocol provideSignalProtocol(IdentityManager identityManager,
      LibP2PManager libP2PManager, AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideSignalProtocol(identityManager, libP2PManager, db));
  }
}
