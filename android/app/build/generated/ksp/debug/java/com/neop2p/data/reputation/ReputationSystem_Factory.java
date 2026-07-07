package com.neop2p.data.reputation;

import com.neop2p.data.local.AppDatabase;
import com.neop2p.data.p2p.IdentityManager;
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
public final class ReputationSystem_Factory implements Factory<ReputationSystem> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<AppDatabase> dbProvider;

  public ReputationSystem_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<AppDatabase> dbProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.dbProvider = dbProvider;
  }

  @Override
  public ReputationSystem get() {
    return newInstance(identityManagerProvider.get(), dbProvider.get());
  }

  public static ReputationSystem_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<AppDatabase> dbProvider) {
    return new ReputationSystem_Factory(identityManagerProvider, dbProvider);
  }

  public static ReputationSystem newInstance(IdentityManager identityManager, AppDatabase db) {
    return new ReputationSystem(identityManager, db);
  }
}
