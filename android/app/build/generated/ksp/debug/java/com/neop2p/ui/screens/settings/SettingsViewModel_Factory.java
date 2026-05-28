package com.neop2p.ui.screens.settings;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<NostrClient> nostrClientProvider;

  public SettingsViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.nostrClientProvider = nostrClientProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(identityManagerProvider.get(), libP2PManagerProvider.get(), nostrClientProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider) {
    return new SettingsViewModel_Factory(identityManagerProvider, libP2PManagerProvider, nostrClientProvider);
  }

  public static SettingsViewModel newInstance(IdentityManager identityManager,
      LibP2PManager libP2PManager, NostrClient nostrClient) {
    return new SettingsViewModel(identityManager, libP2PManager, nostrClient);
  }
}
