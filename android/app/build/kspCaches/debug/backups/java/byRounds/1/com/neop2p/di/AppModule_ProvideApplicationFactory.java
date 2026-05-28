package com.neop2p.di;

import android.app.Application;
import android.content.Context;
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
public final class AppModule_ProvideApplicationFactory implements Factory<Application> {
  private final Provider<Context> appProvider;

  public AppModule_ProvideApplicationFactory(Provider<Context> appProvider) {
    this.appProvider = appProvider;
  }

  @Override
  public Application get() {
    return provideApplication(appProvider.get());
  }

  public static AppModule_ProvideApplicationFactory create(Provider<Context> appProvider) {
    return new AppModule_ProvideApplicationFactory(appProvider);
  }

  public static Application provideApplication(Context app) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideApplication(app));
  }
}
