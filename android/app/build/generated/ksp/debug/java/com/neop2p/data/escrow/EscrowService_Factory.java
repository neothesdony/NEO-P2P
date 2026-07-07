package com.neop2p.data.escrow;

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
public final class EscrowService_Factory implements Factory<EscrowService> {
  private final Provider<AppDatabase> dbProvider;

  public EscrowService_Factory(Provider<AppDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public EscrowService get() {
    return newInstance(dbProvider.get());
  }

  public static EscrowService_Factory create(Provider<AppDatabase> dbProvider) {
    return new EscrowService_Factory(dbProvider);
  }

  public static EscrowService newInstance(AppDatabase db) {
    return new EscrowService(db);
  }
}
