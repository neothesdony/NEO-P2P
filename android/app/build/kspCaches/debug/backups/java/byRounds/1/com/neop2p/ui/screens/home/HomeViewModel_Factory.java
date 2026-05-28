package com.neop2p.ui.screens.home;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
import com.neop2p.data.p2p.NostrClient;
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
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<NostrClient> nostrClientProvider;

  private final Provider<ReputationSystem> reputationSystemProvider;

  public HomeViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.nostrClientProvider = nostrClientProvider;
    this.reputationSystemProvider = reputationSystemProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(identityManagerProvider.get(), libP2PManagerProvider.get(), nostrClientProvider.get(), reputationSystemProvider.get());
  }

  public static HomeViewModel_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider,
      Provider<ReputationSystem> reputationSystemProvider) {
    return new HomeViewModel_Factory(identityManagerProvider, libP2PManagerProvider, nostrClientProvider, reputationSystemProvider);
  }

  public static HomeViewModel newInstance(IdentityManager identityManager,
      LibP2PManager libP2PManager, NostrClient nostrClient, ReputationSystem reputationSystem) {
    return new HomeViewModel(identityManager, libP2PManager, nostrClient, reputationSystem);
  }
}
