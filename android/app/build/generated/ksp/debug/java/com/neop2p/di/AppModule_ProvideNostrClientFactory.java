package com.neop2p.di;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.NostrClient;
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
public final class AppModule_ProvideNostrClientFactory implements Factory<NostrClient> {
  private final Provider<IdentityManager> identityManagerProvider;

  public AppModule_ProvideNostrClientFactory(Provider<IdentityManager> identityManagerProvider) {
    this.identityManagerProvider = identityManagerProvider;
  }

  @Override
  public NostrClient get() {
    return provideNostrClient(identityManagerProvider.get());
  }

  public static AppModule_ProvideNostrClientFactory create(
      Provider<IdentityManager> identityManagerProvider) {
    return new AppModule_ProvideNostrClientFactory(identityManagerProvider);
  }

  public static NostrClient provideNostrClient(IdentityManager identityManager) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideNostrClient(identityManager));
  }
}
