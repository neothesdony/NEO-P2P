package com.neop2p.ui.screens.createoffer;

import com.neop2p.data.escrow.EscrowService;
import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.NostrClient;
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
public final class CreateOfferViewModel_Factory implements Factory<CreateOfferViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<NostrClient> nostrClientProvider;

  private final Provider<EscrowService> escrowServiceProvider;

  public CreateOfferViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<NostrClient> nostrClientProvider, Provider<EscrowService> escrowServiceProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.nostrClientProvider = nostrClientProvider;
    this.escrowServiceProvider = escrowServiceProvider;
  }

  @Override
  public CreateOfferViewModel get() {
    return newInstance(identityManagerProvider.get(), nostrClientProvider.get(), escrowServiceProvider.get());
  }

  public static CreateOfferViewModel_Factory create(
      Provider<IdentityManager> identityManagerProvider, Provider<NostrClient> nostrClientProvider,
      Provider<EscrowService> escrowServiceProvider) {
    return new CreateOfferViewModel_Factory(identityManagerProvider, nostrClientProvider, escrowServiceProvider);
  }

  public static CreateOfferViewModel newInstance(IdentityManager identityManager,
      NostrClient nostrClient, EscrowService escrowService) {
    return new CreateOfferViewModel(identityManager, nostrClient, escrowService);
  }
}
