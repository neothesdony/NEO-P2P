package com.neop2p.data.p2p;

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
public final class WebRTCManager_Factory implements Factory<WebRTCManager> {
  @Override
  public WebRTCManager get() {
    return newInstance();
  }

  public static WebRTCManager_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static WebRTCManager newInstance() {
    return new WebRTCManager();
  }

  private static final class InstanceHolder {
    private static final WebRTCManager_Factory INSTANCE = new WebRTCManager_Factory();
  }
}
