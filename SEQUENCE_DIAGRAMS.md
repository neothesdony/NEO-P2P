# NEO-P2P Sequence Diagrams


```d2
direction: down

User -> UI: Fill offer details (BTC amount, price, methods)
UI -> VM: updateBtcAmount(), updatePrice(), toggleMethod()
User -> UI: Click "Create Offer"
UI -> VM: createOffer(onOfferCreated)
VM -> IdentityManager: getOrCreateIdentity()
IdentityManager --> VM: Return identity
VM -> OfferRepository: saveOffer(offer)
OfferRepository -> LocalDB: Insert offer (status=OPEN, synced=0)
OfferRepository -> NostrClient: publishOffer(offer)
NostrClient --> OfferRepository: Return nostr event ID
OfferRepository -> LocalDB: Update offer (synced=1, nostrId=eventId)
OfferRepository --> VM: Return success
VM --> UI: Offer created successfully
UI -> User: Navigate back to Home screen
User -> UI: See new offer in feed
```

Offer Creation Flow
```
```d2
direction: down

User -> UI: Type message and press send
UI -> VM: sendMessage(text)
VM -> IdentityManager: getOrCreateIdentity()
IdentityManager --> VM: Return identity (peerId, keys)
VM -> SignalProtocol: encrypt(recipientPubkey, message)
SignalProtocol --> VM: Return encrypted bytes
VM -> LibP2PManager: openStream(peerId, "/neop2p/chat/1.0")
alt Stream available
    LibP2PManager --> VM: Return NetworkStream
    VM -> NetworkStream: write(encrypted + metadata)
    NetworkStream --> LibP2PManager: Ack
    LibP2PManager --> VM: Stream success
else No stream/fallback to Nostr
    VM -> NostrClient: publishChatMessage(offerId, encryptedMsg, true)
    NostrClient --> VM: Return event ID
    VM -> LocalDB: Save sent message locally
end
VM -> LocalDB: Save message locally (optimistic update)
VM --> UI: Message sent, clear input
UI -> User: Message appears in chat bubble

%% Receiving messages (simplified)
LibP2PManager -> VM: onStreamData(peerId, encryptedData)
VM -> SignalProtocol: decrypt(privateKey, encryptedData)
SignalProtocol --> VM: Return decrypted message
VM -> LocalDB: Save received message
VM --> UI: New message received
UI -> User: Message appears in chat
```
```
