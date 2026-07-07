package com.neop2p.di;

import com.neop2p.data.escrow.EscrowService;
import com.neop2p.data.local.AppDatabase;
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
public final class AppModule_ProvideEscrowServiceFactory implements Factory<EscrowService> {
  private final Provider<AppDatabase> dbProvider;

  public AppModule_ProvideEscrowServiceFactory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public EscrowService get() {
    return provideEscrowService(dbProvider.get());
  }

  public static AppModule_ProvideEscrowServiceFactory create(Provider<AppDatabase> dbProvider) {
    return new AppModule_ProvideEscrowServiceFactory(dbProvider);
  }

  public static EscrowService provideEscrowService(AppDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideEscrowService(db));
  }
}
