package com.neop2p.di;

import android.content.Context;
import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class AppModule_ProvideLibP2PManagerFactory implements Factory<LibP2PManager> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<Context> contextProvider;

  public AppModule_ProvideLibP2PManagerFactory(Provider<IdentityManager> identityManagerProvider,
      Provider<Context> contextProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public LibP2PManager get() {
    return provideLibP2PManager(identityManagerProvider.get(), contextProvider.get());
  }

  public static AppModule_ProvideLibP2PManagerFactory create(
      Provider<IdentityManager> identityManagerProvider, Provider<Context> contextProvider) {
    return new AppModule_ProvideLibP2PManagerFactory(identityManagerProvider, contextProvider);
  }

  public static LibP2PManager provideLibP2PManager(IdentityManager identityManager,
      Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideLibP2PManager(identityManager, context));
  }
}
