package com.neop2p.data.p2p;

import android.content.Context;
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
public final class LibP2PManager_Factory implements Factory<LibP2PManager> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<Context> contextProvider;

  public LibP2PManager_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<Context> contextProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public LibP2PManager get() {
    return newInstance(identityManagerProvider.get(), contextProvider.get());
  }

  public static LibP2PManager_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<Context> contextProvider) {
    return new LibP2PManager_Factory(identityManagerProvider, contextProvider);
  }

  public static LibP2PManager newInstance(IdentityManager identityManager, Context context) {
    return new LibP2PManager(identityManager, context);
  }
}
