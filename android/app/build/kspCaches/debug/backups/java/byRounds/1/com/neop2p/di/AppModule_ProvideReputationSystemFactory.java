package com.neop2p.di;

import com.neop2p.data.reputation.ReputationSystem;
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
public final class AppModule_ProvideReputationSystemFactory implements Factory<ReputationSystem> {
  @Override
  public ReputationSystem get() {
    return provideReputationSystem();
  }

  public static AppModule_ProvideReputationSystemFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static ReputationSystem provideReputationSystem() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideReputationSystem());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideReputationSystemFactory INSTANCE = new AppModule_ProvideReputationSystemFactory();
  }
}
