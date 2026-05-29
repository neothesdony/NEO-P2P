# Backend/API Integration Design for NEO-P2P

## Overview
NEO-P2P is a zero-backend peer-to-peer cryptocurrency trading application. Instead of a traditional backend, it uses:
- **Nostr** for decentralized messaging and social layer (chat, offer propagation)
- **libp2p** for direct peer-to-peer communication (file exchange, end-to-end encrypted chat, escrow negotiations)
- **Lightning Network** for trustless escrow payments
- **Local storage** (Room on Android, Core Data/SwiftData on iOS) for offline-first operation

This document outlines the API design for integrating these P2P protocols into the shared Kotlin Multiplatform business logic.

## 1. Nostr Integration API

Nostr is used for:
- Publishing and subscribing to trade offers (kind: 32189)
- Chat messages (kind: 1)
- User profiles and attestations (kind: 0)
- Zap receipts for Lightning payments (kind: 9735)

### NostrClient Interface (Shared)
```kotlin
interface NostrClient {
    // Initialization
    suspend fun initialize(): Result<Unit>
    
    // Key management
    suspend fun getOrCreateKeys(): KeyPair
    suspend fun getPublicKey(): String
    
    // Publishing events
    suspend fun publishEvent(event: NostrEvent): Result<String> // returns event ID
    suspend fun publishOffer(offer: TradeOffer): Result<String>
    suspend fun publishChatMessage(offerId: String, message: String, encrypted: Boolean): Result<String>
    suspend fun publishProfile(profile: UserProfile): Result<String>
    
    // Subscribing to events
    fun subscribeToOffers(
        callback: (TradeOffer) -> Unit,
        filters: NostrFilterList = emptyList()
    ): Subscription
    
    fun subscribeToChatMessages(
        offerId: String,
        peerId: String,
        callback: (ChatMessage) -> Unit
    ): Subscription
    
    fun subscribeToProfileUpdates(
        peerId: String,
        callback: (UserProfile) -> Unit
    ): Subscription
    
    // Utility
    fun encryptMessage(publicKeyHex: String, message: String): Result<ByteArray>
    fun decryptMessage(privateKeyHex: String, encrypted: ByteArray): Result<String>
}
```

### NostrEvent Structure
```kotlin
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)
```

### Supported Nostr Kinds
- Kind 0: Profile metadata
- Kind 1: Text notes (chat messages)
- Kind 3: Follows (not used in v1)
- Kind 32189: Trade offers (custom kind for NEO-P2P)
- Kind 9735: Zap receipts (Lightning payments)

### Trade Offer Nostr Structure (Kind 32189)
```json
{
  "pubkey": "<seller_or_buyer_nostr_pubkey>",
  "created_at": 1712345678,
  "kind": 32189,
  "tags": [
    ["offer_type", "buy"], // or "sell"
    ["asset", "BTC"],
    ["fiat_currency", "IDR"],
    ["payment_methods", "bca", "gopay", "dana"],
    ["timestamp", "1712345678"]
  ],
  "content": "{\"offerId\":\"offer_123\",\"cryptoAmountSats\":500000,\"fiatAmount\":7500000,\"pricePerUnit\":1500000000,\"feeSats\":5000,\"status\":\"OPEN\"}",
  "sig": "<signature>"
}
```

### Chat Message Nostr Structure (Kind 1, encrypted)
```json
{
  "pubkey": "<sender_nostr_pubkey>",
  "created_at": 1712345678,
  "kind": 1,
  "tags": [
    ["offer", "<offer_id>"],
    ["recipient", "<recipient_pubkey>"],
    ["encrypted", "true"]
  ],
  "content": "<base64_encrypted_message>",
  "sig": "<signature>"
}
```

## 2. libp2p Integration API

libp2p is used for:
- End-to-end encrypted file exchange (payment proofs)
- Direct peer connections for real-time chat (fallback when Nostr is slow)
- Escrow negotiation and signing
- NAT traversal via built-in relay/circuit systems

### LibP2PManager Interface (Shared)
```kotlin
interface LibP2PManager {
    // Node management
    suspend fun initialize(nodeId: String): Result<Unit>
    suspend fun getPeerId(): String
    suspend fun getAddresses(): List<String>
    
    // Connection management
    suspend fun connectToPeer(peerId: String, multiaddr: String): Result<Unit>
    suspend fun disconnectFromPeer(peerId: String): Result<Unit>
    
    // Stream handling
    fun openStream(
        peerId: String,
        protocol: String,
        callback: (Result<NetworkStream>) -> Unit
    )
    
    // Protocol handlers
    fun setStreamHandler(
        protocol: String,
        handler: (NetworkStream) -> Unit
    )
    
    // File exchange
    suspend fun sendFile(
        peerId: String,
        file: File,
        onProgress: (Double) -> Unit
    ): Result<Unit>
    
    suspend fun receiveFile(
        peerId: String,
        saveTo: File,
        onProgress: (Double) -> Unit
    ): Result<File>
}
```

### NetworkStream Interface
```kotlin
interface NetworkStream {
    suspend fun readAvailable(): ByteArray
    suspend fun write(data: ByteArray): Result<Unit>
    suspend fun close(): Result<Unit>
    val isOpen: Boolean
}
```

### Supported Protocols
- `/neop2p/chat/1.0` - Real-time chat (fallback to Nostr)
- `/neop2p/file/1.0` - Encrypted file transfer (payment proofs)
- `/neop2p/escrow/1.0` - Escrow negotiation and signing
- `/neop2p/webrtc/1.0` - WebRTC signaling for video chat (future)

## 3. Lightning Network Escrow API

The Lightning Network is used for 2-of-3 multisig escrow:
- Keys: Buyer, Seller, Moderator (NEO-P2P fallback)
- Funds are locked in a 2-of-3 multisig address
- Either buyer+seller or buyer+moderator or seller+moderator can release funds

### EscrowService Interface (Shared)
```kotlin
interface EscrowService {
    // Wallet management
    suspend fun initializeWallet(): Result<Unit>
    suspend fun getWalletBalance(): Result<Long> // in satoshis
    suspend fun getDepositAddress(): Result<String> // Bech32
    
    // Escrow creation
    suspend fun createEscrow(
        trade: TradeOffer,
        buyerKey: String, // xpub or extended key for buyer
        sellerKey: String, // xpub or extended key for seller
        moderatorKey: String // NEO-P2P moderator xpub
    ): Result<Escrow>
    
    // Escrow operations
    suspend fun fundEscrow(escrowId: String, txId: String): Result<Unit>
    suspend fun signEscrow(escrowId: String, party: EscrowParty): Result<Unit>
    suspend fun releaseEscrow(escrowId: String): Result<Unit>
    suspend fun disputeEscrow(escrowId: String): Result<Unit>
    suspend fun refundEscrow(escrowId: String): Result<Unit>
    
    // Event listening
    fun listenToEscrowUpdates(
        escrowId: String,
        callback: (EscrowStatus) -> Unit
    ): Subscription
}
```

### Escrow Data Structure
```kotlin
data class Escrow(
    val escrowId: String,
    val offerId: String,
    val escrowType: EscrowType, // LIGHTNING or ON_CHAIN
    val depositAmountSats: Long,
    val tradeAmountSats: Long,
    val feeAmountSats: Long,
    val feeAddress: String, // NEO-P2P fee wallet
    val buyerPeerId: String,
    val sellerPeerId: String,
    val status: EscrowStatus,
    val createdAt: Long,
    val updatedAt: Long
)

enum class EscrowStatus {
    FUNDING, // Waiting for buyer to deposit
    FUNDED, // Deposit confirmed
    SIGNED, // Both parties signed, ready to release
    RELEASED, // Funds released to seller
    REFUNDED, // Funds refunded to buyer
    DISPUTED // In dispute resolution
}
```

## 4. Authentication & Security API

### IdentityManager Interface (Shared)
```kotlin
interface IdentityManager {
    suspend fun getOrCreateIdentity(): Result<Identity>
    suspend fun resetIdentity(): Result<Unit>
    suspend fun backupIdentity(): Result<Backup>
    suspend fun restoreIdentity(backup: Backup): Result<Unit>
    
    // Biometric authentication
    suspend fun authenticateUser(): Result<Boolean>
    suspend fun isBiometricAvailable(): Result<Boolean>
}
```

### Identity Structure
```kotlin
data class Identity(
    val peerId: String, // libp2p peer ID
    val nickname: String?,
    val nostrPubkeyHex: String,
    val lnNodeId: String, // Lightning node ID
    val createdAt: Long,
    val updatedAt: Long
)
```

### Security Features
1. **Biometric/Passkey Authentication**: Protect access to the app and private keys
2. **Secure Storage**: 
   - Android: EncryptedSharedPreferences + Keystore
   - iOS: Keychain + Secure Enclave
3. **End-to-End Encryption**: 
   - Nostr messages encrypted via X25519 (NIP-04)
   - libp2p streams encrypted via TLS 1.3
4. **Certificate Pinning**: For any HTTP fallback (e.g., to Nostr relays via HTTP)
5. **OWASP Mobile Top 10 Protections**:
   - M1: Improper Platform Usage (use platform crypto correctly)
   - M2: Insecure Data Storage (encrypted storage)
   - M3: Insecure Communication (TLS 1.3, E2EE)
   - M4: Insecure Authentication (biometrics, passkeys)
   - M5: Insufficient Cryptography (use AES-256-GCM, X25519)
   - M6: Insecure Authorization (principal of least privilege)
   - M7: Client Code Quality (static analysis, no hardcoded secrets)
   - M8: Code Tampering (signature verification, runtime checks)
   - M9: Reverse Engineering (obfuscation, R8/proguard)
   - M10: Extraneous Functionality (minimal permissions, no debug code)

## 5. Offline Sync Strategy

### Conflict Resolution
- **Last Write Wins (LWW)** with vector clocks for chat messages
- **Merge Functions** for offer updates (price/amount can be updated by owner only)
- **Escrow State** is considered authoritative from the Lightning Network (via block explorer API)

### Sync Triggers
1. App foreground / network change
2. Periodic background sync (every 15 minutes)
3. Manual pull-to-refresh
4. Incoming Nostr/libp2p event

### Local Database Schema (Shared via SQLDelight)
```sql
CREATE TABLE identity (
    peer_id TEXT PRIMARY KEY,
    nickname TEXT,
    nostr_pubkey_hex TEXT NOT NULL,
    ln_node_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE offers (
    offer_id TEXT PRIMARY KEY,
    creator_peer_id TEXT NOT NULL,
    offer_type TEXT NOT NULL, -- BUY/SELL
    crypto_amount_sats INTEGER NOT NULL,
    fiat_amount INTEGER NOT NULL,
    price_per_unit INTEGER NOT NULL,
    fee_sats INTEGER NOT NULL,
    fiat_methods TEXT NOT NULL, -- JSON array
    status TEXT NOT NULL, -- OPEN/ACCEPTED/COMPLETED/CANCELLED
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    synced INTEGER DEFAULT 0 -- 0=local only, 1=sync to Nostr
);

-- Similar tables for chat_messages, escrows, etc.
```

## 6. Platform-Specific Implementation Notes

### Android
- HTTP Client: OkHttp engine for Ktor
- Background Work: WorkManager for periodic sync
- Biometrics: BiometricPrompt API
- Secure Storage: EncryptedSharedPreferences + Android Keystore
- Lightning: Lightning Network Daemon (LND) via gRPC or Breez SDK

### iOS
- HTTP Client: Darwin engine for Ktor (NSURLSession)
- Background Work: BackgroundTasks framework
- Biometrics: LocalAuthentication framework
- Secure Storage: Keychain + Secure Enclave
- Lightning: LND mobile or Breez SDK

## 7. API Contracts Summary

### Key Functions ViewModels Will Call
#### OnboardingViewModel
- `IdentityManager.getOrCreateIdentity()`
- `IdentityManager.saveIdentity(identity)`

#### HomeViewModel
- `OfferRepository.getActiveOffers()` (local + Nostr stream)
- `OfferRepository.saveOffer(offer)` (local + publish to Nostr)

#### CreateOfferViewModel
- `IdentityManager.getOrCreateIdentity()`
- `OfferRepository.saveOffer(offer)`
- `NostrClient.publishOffer(offer)`

#### ChatViewModel
- `LibP2PManager.openStream(peerId, "/neop2p/chat/1.0")`
- `NostrClient.subscribeToChatMessages(offerId, peerId)`
- `SignalProtocol.encrypt/decrypt` (for E2EE)

#### EscrowViewModel
- `EscrowService.createEscrow(...)`
- `EscrowService.fundEscrow(...)`
- `EscrowService.releaseEscrow(...)`
- `LightningNetwork.getTransactionStatus(txId)`

## 8. Implementation Priority (MVP)

1. **Identity System** (create/backup/restore)
2. **Nostr Integration** (publish/subscribe offers)
3. **Chat System** (Nostr kind 1 with encryption)
4. **Offer Creation Flow** (local + Nostr broadcast)
5. **Escrow System** (Lightning 2-of-3 simulation for v1)
6. **File Exchange** (libp2p for payment proofs)
7. **Background Sync** (WorkManager/BackgroundTasks)
8. **Biometric Authentication**
9. **Tor Integration** (optional for privacy)
10. **Moderator Service** (for dispute resolution)

## 9. Testing Strategy

### Unit Tests
- Mock Nostr/libp2p clients
- Test ViewModel state transitions
- Test encryption/decryption functions
- Test data model serialization

### Integration Tests
- Run against test.nostr.dev relays
- Use Lightning Network testnet (signet)
- Test libp2p connections in simulated network

### End-to-End Tests
- Android Emulator + iOS Simulator
- Complete flow: create offer -> chat -> escrow -> complete
- Test offline scenarios and sync recovery

## 10. Security Considerations Checklist

- [ ] All private keys stored in platform secure storage
- [ ] Biometric authentication required for key access
- [ ] Nostr event signing uses RFC6979 deterministic signatures
- [ ] libp2p uses TLS 1.3 with strict certificate validation
- [ ] Lightning invoices verified before payment
- [ ] No logging of sensitive data (keys, mnemonics, messages)
- [ ] Network requests timeout after 10 seconds
- [ ] Rate limiting on Nostr publish to prevent spam
- [ ] Input validation on all incoming Nostr/libp2p data
- [ ] Regular dependency updates (Ktor, Kotlin, kotlinx.serialization)