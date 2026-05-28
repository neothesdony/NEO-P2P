package com.neop2p.service;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
import com.neop2p.data.p2p.NostrClient;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class P2PBackgroundService_MembersInjector implements MembersInjector<P2PBackgroundService> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<NostrClient> nostrClientProvider;

  public P2PBackgroundService_MembersInjector(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.nostrClientProvider = nostrClientProvider;
  }

  public static MembersInjector<P2PBackgroundService> create(
      Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider, Provider<NostrClient> nostrClientProvider) {
    return new P2PBackgroundService_MembersInjector(identityManagerProvider, libP2PManagerProvider, nostrClientProvider);
  }

  @Override
  public void injectMembers(P2PBackgroundService instance) {
    injectIdentityManager(instance, identityManagerProvider.get());
    injectLibP2PManager(instance, libP2PManagerProvider.get());
    injectNostrClient(instance, nostrClientProvider.get());
  }

  @InjectedFieldSignature("com.neop2p.service.P2PBackgroundService.identityManager")
  public static void injectIdentityManager(P2PBackgroundService instance,
      IdentityManager identityManager) {
    instance.identityManager = identityManager;
  }

  @InjectedFieldSignature("com.neop2p.service.P2PBackgroundService.libP2PManager")
  public static void injectLibP2PManager(P2PBackgroundService instance,
      LibP2PManager libP2PManager) {
    instance.libP2PManager = libP2PManager;
  }

  @InjectedFieldSignature("com.neop2p.service.P2PBackgroundService.nostrClient")
  public static void injectNostrClient(P2PBackgroundService instance, NostrClient nostrClient) {
    instance.nostrClient = nostrClient;
  }
}
