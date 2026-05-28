package com.neop2p.data.p2p;

import android.content.Context;
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
public final class IdentityManager_Factory implements Factory<IdentityManager> {
  private final Provider<Context> contextProvider;

  public IdentityManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public IdentityManager get() {
    return newInstance(contextProvider.get());
  }

  public static IdentityManager_Factory create(Provider<Context> contextProvider) {
    return new IdentityManager_Factory(contextProvider);
  }

  public static IdentityManager newInstance(Context context) {
    return new IdentityManager(context);
  }
}
