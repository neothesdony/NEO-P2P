package com.neop2p

import android.Manifest
import android.os.Build
import android.util.Log

/**
 * NEO-P2P global constants.
 *
 * These are the relay addresses and fee wallet hardcoded in the app.
 * Anyone can verify this in the open-source code.
 */
object NeoP2PConfig {
    private const val TAG = "NeoP2PConfig"

    // ─── Fee Wallet (YOUR BTC ADDRESS) ─────────────────────────
    // 0.3% of every trade goes here atomically via pre-signed Lightning payout
    //
    // Signature-protected: the address is signed with an Ed25519 key held ONLY
    // by the project owner (private key in android/fee-wallet-secret.key, never
    // committed). The app embeds the PUBLIC key + a signature over the address.
    // At startup the app verifies the signature. If someone forks the code and
    // changes the fee address, the signature won't match and escrow is BLOCKED.
    // To change the fee address, the owner must re-sign it with the private key.
    const val FEE_WALLET_ADDRESS: String = "tb1q05q8yd60j5ujlqwyfc978jynx9mgpk2l23fg09"

    // Ed25519 PUBLIC key (32 bytes, hex) that signs the fee address.
    // Rotate together with the private key if it ever leaks.
    private const val FEE_WALLET_SIGNER_PUBLIC_KEY: String =
        "573cec9de243821e4179cd553010c2191a54beb1c90fd64f3c69594388c39345"
    // Ed25519 signature (64 bytes, hex) over FEE_WALLET_ADDRESS bytes.
    private const val FEE_WALLET_SIGNATURE_HEX: String =
        "f4b0a3cabe8aaea37227c33b29278a562771710851eacfda3794875f54f8dba722ff34d356fe9525f5422d881f12fb8e0adc0053fa63019ca242b1576baa0b0d"
    const val FEE_PERCENT: Double = 0.003  // 0.3%

    // Platform fee floor (sats): the payout tx adds a separate fee-wallet
    // output, which nodes refuse to relay below the dust threshold. 0.3% of
    // a 50k-sat trade = 150 sats < dust → the fee output was skipped and the
    // fee silently went to the miner. 546 is the conservative P2PKH dust
    // floor (P2WPKH is ~330); applying it at offer creation guarantees the
    // fee output is always relayable.
    const val MIN_FEE_SATS: Long = 546L

    // ─── Arbitrator (Third Key for Dispute Resolution) ──────────
    // Holds the tie-breaking signature in 2-of-3 multisig escrow.
    // The arbitrator reviews evidence (bank receipts) and signs alongside
    // the winning party when a dispute arises.
    // secp256k1 x-only public key (32 bytes hex)
    const val ARBITRATOR_PUBKEY: String = "cd6cc03ba085ba134ce742998d84980103a7c77d85c42631cd154064aa0d3fba"

    // Signature-protected (same scheme as the fee wallet): ARBITRATOR_PUBKEY
    // is signed with an Ed25519 key held ONLY by the project owner (private
    // key in android/arbitrator-signer-secret.key, never committed). The app
    // embeds the PUBLIC key + a signature over the pubkey bytes. At startup
    // the app verifies the signature. If someone forks the code and swaps the
    // arbitrator pubkey (e.g. to steal the tie-break vote), the signature
    // won't match and escrow/dispute paths are BLOCKED. To rotate the
    // arbitrator key, the owner must re-sign it with the private key.

    // Ed25519 PUBLIC key (32 bytes, hex) that signs the arbitrator pubkey —
    // the SAME owner key that signs the fee wallet (fee-wallet-secret.key).
    // Rotate together with the private key if it ever leaks.
    private const val ARBITRATOR_SIGNER_PUBLIC_KEY: String =
        "573cec9de243821e4179cd553010c2191a54beb1c90fd64f3c69594388c39345"
    // Ed25519 signature (64 bytes, hex) over ARBITRATOR_PUBKEY bytes
    // (the ASCII hex-string bytes, matching verifyArbitratorIntegrity),
    // produced with the owner's fee-wallet signing key.
    private const val ARBITRATOR_SIGNATURE_HEX: String =
        "16ecf5dd75e80ad75298f62bdddcbd786a71aaa10186ff626d956107901b23354a82041b2f96f89f3a5759606492ff73c6dc9aca1137421cfb99b9c58e966c08"

    // ─── Default Nostr Relays ──────────────────────────────────
    // You control these on Oracle Free Tier
    // Users can add/remove relays in settings
    val DEFAULT_NOSTR_RELAYS: List<String> = listOf(
        "wss://relay1.custom-minipc.com:7001",
        "wss://relay2.custom-minipc.com:7002",
        "wss://relay3.custom-minipc.com:7003",
        "wss://meta.custom-minipc.com:7004",    // NIP-65 metadata relay
        "wss://nos.lol",                  // Fallback public relay
        "wss://relay.damus.io",           // Fallback public relay
    )

    // ─── Default libp2p Circuit Relays ─────────────────────────
    val DEFAULT_LIBP2P_RELAYS: List<String> = listOf(
        "/dns/relay1.custom-minipc.com/tcp/4001/p2p-circuit"
    )

    // ─── TURN/STUN Servers (last resort NAT traversal) ─────────
    // Credentials injected via BuildConfig (from local.properties, never in source)
    val TURN_SERVERS: List<TurnServerConfig> = listOf(
        TurnServerConfig(
            uri = "turn:relay1.custom-minipc.com:3478",
            username = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_CREDENTIAL
        ),
        TurnServerConfig(
            uri = "stun:relay1.custom-minipc.com:3478",
            username = null,
            credential = null
        ),
        TurnServerConfig(
            uri = "stun:stun.l.google.com:19302",
            username = null,
            credential = null
        )
    )

    // ─── Supported Fiat Methods (Indonesia) ────────────────────
    val FIAT_METHODS: List<FiatMethod> = FiatMethod.entries.toList()

    // ─── Network Timeouts ─────────────────────────────────────
    const val LIBP2P_CONNECTION_TIMEOUT_MS: Long = 15_000L
    const val NOSTR_SUB_TIMEOUT_MS: Long = 10_000L
    const val WEBRTC_CONNECTION_TIMEOUT_MS: Long = 20_000L
    const val LIGHTNING_PAYMENT_TIMEOUT_MS: Long = 60_000L
    const val KEEPALIVE_INTERVAL_MS: Long = 30_000L

    // ─── Market Price ──────────────────────────────────────────
    // Fallback reference price for BTC in IDR (used to pre-fill the
    // "Price per BTC" field in the Create Offer form). This is a static
    // placeholder until a live market-price feed is wired up.
    const val DEFAULT_BTC_MARKET_PRICE_IDR: Long = 1_000_000_000  // ~Rp 1.0M/BTC placeholder

    // ─── Android Permissions ──────────────────────────────────
    val REQUIRED_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.INTERNET)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Verifies the arbitrator pubkey by checking its Ed25519 signature.
     * Call once at app startup.
     *
     * Returns true if the embedded signature matches ARBITRATOR_PUBKEY.
     * If someone forks the code and swaps the arbitrator pubkey (without the
     * owner's private key to re-sign it), this returns false and blocks
     * escrow creation + dispute resolution.
     */
    fun verifyArbitratorIntegrity(): Boolean {
        return try {
            val pubKey = org.bouncycastle.util.encoders.Hex.decode(ARBITRATOR_SIGNER_PUBLIC_KEY)
            val expectedSig = org.bouncycastle.util.encoders.Hex.decode(ARBITRATOR_SIGNATURE_HEX)
            val msg = ARBITRATOR_PUBKEY.encodeToByteArray()

            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubKey, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(msg, 0, msg.size)
            val valid = verifier.verifySignature(expectedSig)

            if (valid) {
                Log.i(TAG, "Arbitrator integrity verified: $ARBITRATOR_PUBKEY")
            } else {
                Log.wtf(TAG,
                    "🚨 ARBITRATOR PUBKEY HAS BEEN TAMPERED WITH OR RE-SIGNED! " +
                        "DO NOT USE THIS BUILD — dispute resolutions can be hijacked.")
            }
            valid
        } catch (e: Exception) {
            Log.wtf(TAG, "🚨 Arbitrator signature verification FAILED: ${e.message}", e)
            false
        }
    }

    /**
     * Verifies the fee wallet address by checking its Ed25519 signature.
     * Call once at app startup.
     *
     * Returns true if the embedded signature matches FEE_WALLET_ADDRESS.
     * If someone forks the code and changes the address (without the owner's
     * private key to re-sign it), this returns false and blocks escrow.
     */
    fun verifyFeeWalletIntegrity(): Boolean {
        return try {
            val pubKey = org.bouncycastle.util.encoders.Hex.decode(FEE_WALLET_SIGNER_PUBLIC_KEY)
            val expectedSig = org.bouncycastle.util.encoders.Hex.decode(FEE_WALLET_SIGNATURE_HEX)
            val msg = FEE_WALLET_ADDRESS.encodeToByteArray()

            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubKey, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(msg, 0, msg.size)
            val valid = verifier.verifySignature(expectedSig)

            if (valid) {
                Log.i(TAG, "Fee wallet integrity verified: $FEE_WALLET_ADDRESS")
            } else {
                Log.wtf(TAG,
                    "🚨 FEE WALLET ADDRESS HAS BEEN TAMPERED WITH OR RE-SIGNED! " +
                    "DO NOT USE THIS BUILD — fees will go to an unexpected address.")
            }
            valid
        } catch (e: Exception) {
            Log.wtf(TAG, "🚨 Fee wallet signature verification FAILED: ${e.message}", e)
            false
        }
    }
}

data class TurnServerConfig(
    val uri: String,
    val username: String?,
    val credential: String?
)

enum class FiatMethod(val displayNameId: String, val id: String) {
    BCA_TRANSFER("BCA Transfer", "bca"),
    MANDIRI_TRANSFER("Mandiri Transfer", "mandiri"),
    BNI_TRANSFER("BNI Transfer", "bni"),
    BRI_TRANSFER("BRI Transfer", "bri"),
    CIMB_TRANSFER("CIMB Transfer", "cimb"),
    JAGO_TRANSFER("Jago Transfer", "jago"),
    SEABANK_TRANSFER("SeaBank Transfer", "seabank"),
    QRIS("QRIS", "qris"),
    GOPAY("GoPay", "gopay"),
    OVO("OVO", "ovo"),
    DANA("Dana", "dana"),
    SHOPEEPAY("ShopeePay", "shopeepay"),
    LINKAJA("LinkAja", "linkaja"),
    CASH_MEETUP("Cash Meetup (Tunai)", "cash");

    companion object {
        fun fromId(id: String): FiatMethod? = entries.find { it.id == id }
    }
}
