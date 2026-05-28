package com.neop2p.data.reputation;

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
public final class ReputationSystem_Factory implements Factory<ReputationSystem> {
  @Override
  public ReputationSystem get() {
    return newInstance();
  }

  public static ReputationSystem_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static ReputationSystem newInstance() {
    return new ReputationSystem();
  }

  private static final class InstanceHolder {
    private static final ReputationSystem_Factory INSTANCE = new ReputationSystem_Factory();
  }
}
