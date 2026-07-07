package com.neop2p

import android.Manifest
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * NEO-P2P global constants.
 *
 * These are the relay addresses and fee wallet hardcoded in the app.
 * Anyone can verify this in the open-source code.
 */
object NeoP2PConfig {
    private const val TAG = "NeoP2PConfig"

    // ─── Fee Wallet (YOUR BTC ADDRESS) ─────────────────────────
    // 1% of every trade goes here atomically via pre-signed Lightning payout
    // Integrity-protected: FEE_WALLET_HASH is checked at runtime to detect tampering.
    // If someone forks the code and changes the address, the app will warn on every startup.
    const val FEE_WALLET_ADDRESS: String = "bc1qdfs8ucuq8dm3k3tfuzlvhfyevhs0swz4098fwk"
    // SHA-256 hash of the expected address (hex) — used for tamper detection
    private const val FEE_WALLET_EXPECTED_HASH: String =
        "900c3ebb921c8479f0eadcbb8aeff5ac50ce90a1e5369f57bcdd95d72f578317"
    const val FEE_PERCENT: Double = 0.01  // 1%

    // ─── Arbitrator (Third Key for Dispute Resolution) ──────────
    // Holds the tie-breaking signature in 2-of-3 multisig escrow.
    // The arbitrator reviews evidence (bank receipts) and signs alongside
    // the winning party when a dispute arises.
    // For dev/prototype: a placeholder — replace with your actual key.
    const val ARBITRATOR_PUBKEY: String = "ARBITRATOR_PUBKEY_PLACEHOLDER"
    const val DISPUTE_TIMELOCK_DAYS: Int = 7

    // ─── Default Nostr Relays ──────────────────────────────────
    // You control these on Oracle Free Tier
    // Users can add/remove relays in settings
    val DEFAULT_NOSTR_RELAYS: List<String> = listOf(
        "wss://relay1.neop2p.io:7001",
        "wss://relay2.neop2p.io:7002",
        "wss://relay3.neop2p.io:7003",
        "wss://meta.neop2p.io:7004",    // NIP-65 metadata relay
        "wss://nos.lol",                  // Fallback public relay
        "wss://relay.damus.io",           // Fallback public relay
    )

    // ─── Default libp2p Circuit Relays ─────────────────────────
    val DEFAULT_LIBP2P_RELAYS: List<String> = listOf(
        "/dns/relay1.neop2p.io/tcp/4001/p2p-circuit"
    )

    // ─── TURN/STUN Servers (last resort NAT traversal) ─────────
    // Credentials injected via BuildConfig (from local.properties, never in source)
    val TURN_SERVERS: List<TurnServerConfig> = listOf(
        TurnServerConfig(
            uri = "turn:relay1.neop2p.io:3478",
            username = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_CREDENTIAL
        ),
        TurnServerConfig(
            uri = "stun:relay1.neop2p.io:3478",
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

    // ─── Android Permissions ──────────────────────────────────
    val REQUIRED_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.INTERNET)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Verifies the integrity of the fee wallet address.
     * Call once at app startup. Logs a CRITICAL warning if the address
     * has been tampered with (someone forked the code and changed it).
     */
    fun verifyFeeWalletIntegrity(): Boolean {
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(FEE_WALLET_ADDRESS.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val valid = actualHash == FEE_WALLET_EXPECTED_HASH
        if (!valid) {
            Log.wtf(TAG,
                "🚨 FEE WALLET ADDRESS HAS BEEN TAMPERED WITH! " +
                "Expected hash: $FEE_WALLET_EXPECTED_HASH, " +
                "Got: $actualHash. " +
                "DO NOT USE THIS BUILD — fees will go to an unexpected address."
            )
        } else {
            Log.i(TAG, "Fee wallet integrity verified: $FEE_WALLET_ADDRESS")
        }
        return valid
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
