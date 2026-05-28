package com.neop2p.di;

import android.content.Context;
import com.neop2p.data.p2p.IdentityManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class AppModule_ProvideIdentityManagerFactory implements Factory<IdentityManager> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideIdentityManagerFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public IdentityManager get() {
    return provideIdentityManager(contextProvider.get());
  }

  public static AppModule_ProvideIdentityManagerFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideIdentityManagerFactory(contextProvider);
  }

  public static IdentityManager provideIdentityManager(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideIdentityManager(context));
  }
}
