package com.neop2p.ui.screens.chat;

import com.neop2p.data.p2p.IdentityManager;
import com.neop2p.data.p2p.LibP2PManager;
import com.neop2p.data.p2p.SignalProtocol;
import com.neop2p.data.p2p.WebRTCManager;
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
public final class ChatViewModel_Factory implements Factory<ChatViewModel> {
  private final Provider<IdentityManager> identityManagerProvider;

  private final Provider<LibP2PManager> libP2PManagerProvider;

  private final Provider<SignalProtocol> signalProtocolProvider;

  private final Provider<WebRTCManager> webRTCManagerProvider;

  public ChatViewModel_Factory(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider,
      Provider<SignalProtocol> signalProtocolProvider,
      Provider<WebRTCManager> webRTCManagerProvider) {
    this.identityManagerProvider = identityManagerProvider;
    this.libP2PManagerProvider = libP2PManagerProvider;
    this.signalProtocolProvider = signalProtocolProvider;
    this.webRTCManagerProvider = webRTCManagerProvider;
  }

  @Override
  public ChatViewModel get() {
    return newInstance(identityManagerProvider.get(), libP2PManagerProvider.get(), signalProtocolProvider.get(), webRTCManagerProvider.get());
  }

  public static ChatViewModel_Factory create(Provider<IdentityManager> identityManagerProvider,
      Provider<LibP2PManager> libP2PManagerProvider,
      Provider<SignalProtocol> signalProtocolProvider,
      Provider<WebRTCManager> webRTCManagerProvider) {
    return new ChatViewModel_Factory(identityManagerProvider, libP2PManagerProvider, signalProtocolProvider, webRTCManagerProvider);
  }

  public static ChatViewModel newInstance(IdentityManager identityManager,
      LibP2PManager libP2PManager, SignalProtocol signalProtocol, WebRTCManager webRTCManager) {
    return new ChatViewModel(identityManager, libP2PManager, signalProtocol, webRTCManager);
  }
}
