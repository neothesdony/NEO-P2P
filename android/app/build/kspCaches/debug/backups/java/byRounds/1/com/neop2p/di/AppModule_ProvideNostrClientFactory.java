package com.neop2p.di;

import com.neop2p.data.p2p.NostrClient;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
  @Override
  public NostrClient get() {
    return provideNostrClient();
  }

  public static AppModule_ProvideNostrClientFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static NostrClient provideNostrClient() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideNostrClient());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideNostrClientFactory INSTANCE = new AppModule_ProvideNostrClientFactory();
  }
}
