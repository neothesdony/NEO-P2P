package com.neop2p.ui.screens.profile;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.reputation.ReputationSystem;
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
public final class ProfileViewModel_Factory implements Factory<ProfileViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<ReputationSystem> reputationSystemProvider;

  public ProfileViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.reputationSystemProvider = reputationSystemProvider;
  }

  @Override
  public ProfileViewModel get() {
    return newInstance(identityManagerProvider.get(), reputationSystemProvider.get());
  }

  public static ProfileViewModel_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    return new ProfileViewModel_Factory(identityManagerProvider, reputationSystemProvider);
  }

  public static ProfileViewModel newInstance(IdentityManager identityManager,
      ReputationSystem reputationSystem) {
    return new ProfileViewModel(identityManager, reputationSystem);
  }
}
