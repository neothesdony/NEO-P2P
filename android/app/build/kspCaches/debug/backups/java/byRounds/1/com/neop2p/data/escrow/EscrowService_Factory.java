package com.neop2p.data.escrow;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class EscrowService_Factory implements Factory<EscrowService> {
  @Override
  public EscrowService get() {
    return newInstance();
  }

  public static EscrowService_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static EscrowService newInstance() {
    return new EscrowService();
  }

  private static final class InstanceHolder {
    private static final EscrowService_Factory INSTANCE = new EscrowService_Factory();
  }
}
