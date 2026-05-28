package com.neop2p.di;

import com.neop2p.data.escrow.EscrowService;
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
public final class AppModule_ProvideEscrowServiceFactory implements Factory<EscrowService> {
  @Override
  public EscrowService get() {
    return provideEscrowService();
  }

  public static AppModule_ProvideEscrowServiceFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static EscrowService provideEscrowService() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideEscrowService());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideEscrowServiceFactory INSTANCE = new AppModule_ProvideEscrowServiceFactory();
  }
}
