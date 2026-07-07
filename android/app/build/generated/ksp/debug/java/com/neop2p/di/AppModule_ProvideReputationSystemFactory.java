package com.neop2p.di;

import com.neop2p.data.local.AppDatabase;
import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.reputation.ReputationSystem;
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
public final class AppModule_ProvideReputationSystemFactory implements Factory<ReputationSystem> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideReputationSystemFactory(Provider<IdentityManager> identityManagerProvider,
      Provider<AppDatabase> dbProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.dbProvider = dbProvider;
  }

  @Override
  public ReputationSystem get() {
    return provideReputationSystem(identityManagerProvider.get(), dbProvider.get());
  }

  public static AppModule_ProvideReputationSystemFactory create(
      Provider<IdentityManager> identityManagerProvider, Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideReputationSystemFactory(identityManagerProvider, dbProvider);
  }

  public static ReputationSystem provideReputationSystem(IdentityManager identityManager,
      AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideReputationSystem(identityManager, db));
  }
}
