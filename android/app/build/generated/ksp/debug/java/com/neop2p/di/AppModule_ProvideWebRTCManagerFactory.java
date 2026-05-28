package com.neop2p.di;

import com.neop2p.data.p2p.WebRTCManager;
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
public final class AppModule_ProvideWebRTCManagerFactory implements Factory<WebRTCManager> {
  @Override
  public WebRTCManager get() {
    return provideWebRTCManager();
  }

  public static AppModule_ProvideWebRTCManagerFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static WebRTCManager provideWebRTCManager() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideWebRTCManager());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideWebRTCManagerFactory INSTANCE = new AppModule_ProvideWebRTCManagerFactory();
  }
}
