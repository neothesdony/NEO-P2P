package com.neop2p.ui.screens.onboarding;

import com.neop2p.data.p2p.IdentityManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class OnboardingViewModel_Factory implements Factory<OnboardingViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  public OnboardingViewModel_Factory(Provider<IdentityManager> identityManagerProvider) {
    this.identityManagerProvider = identityManagerProvider;
  }

  @Override
  public OnboardingViewModel get() {
    return newInstance(identityManagerProvider.get());
  }

  public static OnboardingViewModel_Factory create(
      Provider<IdentityManager> identityManagerProvider) {
    return new OnboardingViewModel_Factory(identityManagerProvider);
  }

  public static OnboardingViewModel newInstance(IdentityManager identityManager) {
    return new OnboardingViewModel(identityManager);
  }
}
