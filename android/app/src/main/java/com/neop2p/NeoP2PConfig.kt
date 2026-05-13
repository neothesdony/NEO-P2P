package com.neop2p

import android.Manifest
import android.os.Build

/**
 * NEO-P2P global constants.
 *
 * These are the relay addresses and fee wallet hardcoded in the app.
 * Anyone can verify this in the open-source code.
 */
object NeoP2PConfig {

    // ─── Fee Wallet (YOUR BTC ADDRESS) ─────────────────────────
    // 1% of every trade goes here atomically via pre-signed Lightning payout
    // CHANGE THIS to your real BTC address before building
    const val FEE_WALLET_ADDRESS: String = "bc1q_neop2p_fee_wallet_replace_me"
    const val FEE_PERCENT: Double = 0.01  // 1%

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
    val TURN_SERVERS: List<TurnServerConfig> = listOf(
        TurnServerConfig(
            uri = "turn:relay1.neop2p.io:3478",
            username = "neop2p",
            credential = "changeme"  // CHANGE THIS
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
