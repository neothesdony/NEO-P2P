package com.neop2p.ui.screens.escrow;

import com.neop2p.data.escrow.EscrowService;
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
public final class EscrowViewModel_Factory implements Factory<EscrowViewModel> {
  private final Provider<EscrowService> escrowServiceProvider;

  public EscrowViewModel_Factory(Provider<EscrowService> escrowServiceProvider) {
    this.escrowServiceProvider = escrowServiceProvider;
  }

  @Override
  public EscrowViewModel get() {
    return newInstance(escrowServiceProvider.get());
  }

  public static EscrowViewModel_Factory create(Provider<EscrowService> escrowServiceProvider) {
    return new EscrowViewModel_Factory(escrowServiceProvider);
  }

  public static EscrowViewModel newInstance(EscrowService escrowService) {
    return new EscrowViewModel(escrowService);
  }
}
