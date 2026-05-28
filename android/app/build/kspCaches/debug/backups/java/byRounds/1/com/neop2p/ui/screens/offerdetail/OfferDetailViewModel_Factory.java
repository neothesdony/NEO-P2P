package com.neop2p.ui.screens.offerdetail;

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
public final class OfferDetailViewModel_Factory implements Factory<OfferDetailViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<ReputationSystem> reputationSystemProvider;

  public OfferDetailViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.reputationSystemProvider = reputationSystemProvider;
  }

  @Override
  public OfferDetailViewModel get() {
    return newInstance(identityManagerProvider.get(), reputationSystemProvider.get());
  }

  public static OfferDetailViewModel_Factory create(
      Provider<IdentityManager> identityManagerProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    return new OfferDetailViewModel_Factory(identityManagerProvider, reputationSystemProvider);
  }

  public static OfferDetailViewModel newInstance(IdentityManager identityManager,
      ReputationSystem reputationSystem) {
    return new OfferDetailViewModel(identityManager, reputationSystem);
  }
}
