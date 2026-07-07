package com.neop2p.data.p2p;

import com.neop2p.data.local.AppDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class SignalProtocol_Factory implements Factory<SignalProtocol> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<AppDatabase> dbProvider;

  public SignalProtocol_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<AppDatabase> dbProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.dbProvider = dbProvider;
  }

  @Override
  public SignalProtocol get() {
    return newInstance(identityManagerProvider.get(), libP2PManagerProvider.get(), dbProvider.get());
  }

  public static SignalProtocol_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<AppDatabase> dbProvider) {
    return new SignalProtocol_Factory(identityManagerProvider, libP2PManagerProvider, dbProvider);
  }

  public static SignalProtocol newInstance(IdentityManager identityManager,
      LibP2PManager libP2PManager, AppDatabase db) {
    return new SignalProtocol(identityManager, libP2PManager, db);
  }
}
