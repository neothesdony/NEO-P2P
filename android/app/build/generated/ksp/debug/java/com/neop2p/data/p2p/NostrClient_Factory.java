package com.neop2p.data.p2p;

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
public final class NostrClient_Factory implements Factory<NostrClient> {
  private final Provider<IdentityManager> identityManagerProvider;

  public NostrClient_Factory(Provider<IdentityManager> identityManagerProvider) {
    this.identityManagerProvider = identityManagerProvider;
  }

  @Override
  public NostrClient get() {
    return newInstance(identityManagerProvider.get());
  }

  public static NostrClient_Factory create(Provider<IdentityManager> identityManagerProvider) {
    return new NostrClient_Factory(identityManagerProvider);
  }

  public static NostrClient newInstance(IdentityManager identityManager) {
    return new NostrClient(identityManager);
  }
}
