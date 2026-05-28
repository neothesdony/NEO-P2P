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
public final class NostrClient_Factory implements Factory<NostrClient> {
  @Override
  public NostrClient get() {
    return newInstance();
  }

  public static NostrClient_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static NostrClient newInstance() {
    return new NostrClient();
  }

  private static final class InstanceHolder {
    private static final NostrClient_Factory INSTANCE = new NostrClient_Factory();
  }
}
