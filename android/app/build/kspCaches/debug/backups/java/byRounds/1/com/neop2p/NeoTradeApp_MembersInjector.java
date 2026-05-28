package com.neop2p;

import androidx.hilt.work.HiltWorkerFactory;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class NeoTradeApp_MembersInjector implements MembersInjector<NeoTradeApp> {
  private final Provider<HiltWorkerFactory> workerFactoryProvider;

  public NeoTradeApp_MembersInjector(Provider<HiltWorkerFactory> workerFactoryProvider) {
    this.workerFactoryProvider = workerFactoryProvider;
  }

  public static MembersInjector<NeoTradeApp> create(
      Provider<HiltWorkerFactory> workerFactoryProvider) {
    return new NeoTradeApp_MembersInjector(workerFactoryProvider);
  }

  @Override
  public void injectMembers(NeoTradeApp instance) {
    injectWorkerFactory(instance, workerFactoryProvider.get());
  }

  @InjectedFieldSignature("com.neop2p.NeoTradeApp.workerFactory")
  public static void injectWorkerFactory(NeoTradeApp instance, HiltWorkerFactory workerFactory) {
    instance.workerFactory = workerFactory;
  }
}
