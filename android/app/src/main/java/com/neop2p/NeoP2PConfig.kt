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
    // 0.5% of every trade goes here atomically via pre-signed payout
    //
    // Signature-protected: the address is signed with an Ed25519 key held ONLY
    // by the project owner (private key in android/fee-wallet-secret.key, never
    // committed). The app embeds the PUBLIC key + a signature over the address.
    // At startup the app verifies the signature. If someone forks the code and
    // changes the fee address, the signature won't match and escrow is BLOCKED.
    // To change the fee address, the owner must re-sign it with the private key.
    //
    // Network-aware (2026-09-13, cfaa566 regression): the payout tx parses this
    // address with the network params selected by BuildConfig.NETWORK
    // (EscrowService.NET_PARAMS). A mainnet bc1… address on a testnet build (or
    // vice versa) throws InvalidCharacter at payout-build time and NO trade can
    // complete. Both signed trios are embedded and the one matching NETWORK is
    // selected, so a single source tree produces valid mainnet AND testnet APKs.
    const val FEE_WALLET_ADDRESS_MAINNET: String =
        "bc1qdfs8ucuq8dm3k3tfuzlvhfyevhs0swz4098fwk"
    const val FEE_WALLET_ADDRESS_TESTNET: String =
        "tb1q05q8yd60j5ujlqwyfc978jynx9mgpk2l23fg09"

    val FEE_WALLET_ADDRESS: String = feeWalletAddress(BuildConfig.NETWORK)

    private fun feeWalletAddress(network: String): String =
        if (network == "mainnet") FEE_WALLET_ADDRESS_MAINNET else FEE_WALLET_ADDRESS_TESTNET

    // Ed25519 PUBLIC key (32 bytes, hex) that signs the fee address.
    // Rotate together with the private key if it ever leaks.
    // Mainnet is the RELEASE key (release-fee-wallet-secret.key); testnet is the
    // dev key (fee-wallet-secret.key) that also signs the arbitrator pubkey.
    private fun feeWalletSignerPublicKey(network: String): String =
        if (network == "mainnet")
            "5d4ca0e0b20fedb21e81670704bcbe7f90dfccf6e82df4c7565a3693ae3cdb13"
        else
            "573cec9de243821e4179cd553010c2191a54beb1c90fd64f3c69594388c39345"
    // Ed25519 signature (64 bytes, hex) over the network's fee address bytes.
    private fun feeWalletSignature(network: String): String =
        if (network == "mainnet")
            "c7d831c55c3b6f7db08b3853179f10f2f6038f4913af4d02c35270bafcd2446a7165e86d363d2b164becb78c1e57a1b3d1d48759238722ebaea45588b6f03507"
        else
            "f4b0a3cabe8aaea37227c33b29278a562771710851eacfda3794875f54f8dba722ff34d356fe9525f5422d881f12fb8e0adc0053fa63019ca242b1576baa0b0d"
    const val FEE_PERCENT: Double = 0.005  // 0.5%

    // Integer form of the 0.5% platform fee, for exact money math.
    // feeSats = (sats * FEE_NUM) / FEE_DEN  — exact for every Long.
    const val FEE_NUM: Long = 5
    const val FEE_DEN: Long = 1000

    // Platform fee floor (sats): the payout tx adds a separate fee-wallet
    // output, which nodes refuse to relay below the dust threshold. 0.5% of
    // a 50k-sat trade = 250 sats < dust → the fee output was skipped and the
    // fee silently went to the miner. 546 is the conservative P2PKH dust
    // floor (P2WPKH is ~330); applying it at offer creation guarantees the
    // fee output is always relayable.
    const val MIN_FEE_SATS: Long = 546L

    // ─── Offer field bounds (C5/D8, 2026-09-01) ─────────────────
    // Field-level ingest gate for remote offers (OfferRouter.isValidOfferPayload).
    // The LXMF byte caps bound the container; these bound the money fields so a
    // hostile peer cannot inject absurd magnitudes into the feed (and unclamped
    // Long money math is the only overflow surface left).
    const val MIN_OFFER_SATS: Long = 1_000L              // dust floor sanity
    const val MAX_OFFER_SATS: Long = 100_000_000L        // 1 BTC
    const val MIN_OFFER_FIAT_IDR: Long = 5_000_000L      // Rp 5M minimum trade
    const val MAX_OFFER_FIAT_IDR: Long = 100_000_000_000L // Rp 100B headroom
    const val MAX_OFFER_PRICE: Double = 10_000_000_000.0  // Rp 10B/BTC
    const val MAX_OFFER_FIAT_METHODS: Int = 14             // FiatMethod.entries.size
    const val MAX_OFFER_FIAT_METHOD_LENGTH: Int = 64

    // Nickname cap (C10/I6): enforced at write (IdentityManager.updateNickname)
    // and at offer ingest. Matches the onboarding input cap of 32 chars.
    const val MAX_NICKNAME_LENGTH: Int = 32

    // ─── Arbitrator (Third Key for Dispute Resolution) ──────────
    // Holds the tie-breaking signature in 2-of-3 multisig escrow.
    // The arbitrator reviews evidence (bank receipts) and signs alongside
    // the winning party when a dispute arises.
    // secp256k1 x-only public key (32 bytes hex)
    const val ARBITRATOR_PUBKEY: String = "cd6cc03ba085ba134ce742998d84980103a7c77d85c42631cd154064aa0d3fba"

    // The arbitrator's libp2p peerId (RNS displayName) — the LXMF delivery
    // destination for dispute/evidence/resolution messages on the RNS path
    // (Phase 3). Blank = RNS arbitration delivery disabled (the parties never
    // deliver to the arbitrator; disputes/evidence still reach the
    // COUNTERPARTY). Set it to the ADMIN DEVICE's own peerId — visible in
    // the app under Profile → "NEO-P2P peer ID" (or the Invite QR link,
    // `neop2p://peer/<peerId>`) — to enable end-to-end arbitration over LXMF.
    // The peerId is the Ed25519 identity hash (libp2p base58), distinct from
    // ARBITRATOR_PUBKEY (a secp256k1 x-only key); it cannot be derived from
    // the pubkey and is not a secret. Arbitrator delivery is best-effort:
    // an offline arbitrator does not block dispute opening (the 60s sweep
    // retries pending disputes/evidence/resolutions).
    const val ARBITRATOR_PEER_ID: String = "12D3KooWA2QKwyiVtZsmpwUSqrGhL6m32XiVqWPeQLJWV9jvfTu7"

    // ─── RNS Transport Node (Phase 4) ─────────────────────────
    // The VPS transport node (rnsd-kt, enableTransport=true, TCP server
    // interface). Phones connect as TCP clients; the node routes announces,
    // paths, and links between peers and to the LXMF propagation node.
    // Blank host = no network interface (loopback-only, tests).
    const val RNS_TRANSPORT_NODE_HOST: String = "relay1.custom-minipc.com"
    const val RNS_TRANSPORT_NODE_PORT: Int = 42420

    // ─── Secondary RNS Transport Node (H1, 2026-09-11) ─────────
    // Second transport node on a different host. The app connects it via
    // TransportFailover when the primary is offline (additive — the primary
    // constant above is NOT changed). Blank host = disabled.
    const val SECONDARY_TRANSPORT_NODE_HOST: String = ""
    const val SECONDARY_TRANSPORT_NODE_PORT: Int = 42420

    // Signature-protected (same scheme as the fee wallet): ARBITRATOR_PUBKEY
    // is signed with an Ed25519 key held ONLY by the project owner (private
    // key in android/arbitrator-signer-secret.key, never committed). The app
    // embeds the PUBLIC key + a signature over the pubkey bytes. At startup
    // the app verifies the signature. If someone forks the code and swaps the
    // arbitrator pubkey (e.g. to steal the tie-break vote), the signature
    // won't match and escrow/dispute paths are BLOCKED. To rotate the
    // arbitrator key, the owner must re-sign it with the private key.

    // Ed25519 PUBLIC key (32 bytes, hex) that signs the arbitrator pubkey —
    // the original owner key (fee-wallet-secret.key). The release/mainnet fee
    // wallet is signed by a separate key (release-fee-wallet-secret.key).
    // Rotate together with the private key if it ever leaks.
    private const val ARBITRATOR_SIGNER_PUBLIC_KEY: String =
        "573cec9de243821e4179cd553010c2191a54beb1c90fd64f3c69594388c39345"
    // Ed25519 signature (64 bytes, hex) over ARBITRATOR_PUBKEY bytes
    // (the ASCII hex-string bytes, matching verifyArbitratorIntegrity),
    // produced with the owner's fee-wallet signing key.
    private const val ARBITRATOR_SIGNATURE_HEX: String =
        "16ecf5dd75e80ad75298f62bdddcbd786a71aaa10186ff626d956107901b23354a82041b2f96f89f3a5759606492ff73c6dc9aca1137421cfb99b9c58e966c08"

    // ─── Supported Fiat Methods (Indonesia) ────────────────────
    val FIAT_METHODS: List<FiatMethod> = FiatMethod.entries.toList()

    // ─── Network Timeouts ─────────────────────────────────────
    const val KEEPALIVE_INTERVAL_MS: Long = 30_000L

    // A MATCHED offer whose escrow is never created within this window is
    // auto-CANCELLED by the orchestrator sweep (role-gated to the creator).
    // The buyer has 1h from the match to see the seller's escrow and fund
    // it; past that the lock is dead weight on the feed.
    const val MATCHED_ESCROW_TIMEOUT_MS: Long = 60L * 60 * 1000

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
    fun verifyFeeWalletIntegrity(): Boolean = verifyFeeWalletIntegrity(BuildConfig.NETWORK)

    /**
     * Same as [verifyFeeWalletIntegrity] but for an explicit chain. Exposed so
     * a test can assert BOTH the mainnet and testnet trios are self-consistent:
     * a temporary testnet debug build must never carry a mainnet fee address.
     */
    fun verifyFeeWalletIntegrity(network: String): Boolean {
        return try {
            val pubKey = org.bouncycastle.util.encoders.Hex.decode(feeWalletSignerPublicKey(network))
            val expectedSig = org.bouncycastle.util.encoders.Hex.decode(feeWalletSignature(network))
            val msg = feeWalletAddress(network).encodeToByteArray()

            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubKey, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(msg, 0, msg.size)
            val valid = verifier.verifySignature(expectedSig)

            if (valid) {
                Log.i(TAG, "Fee wallet integrity verified: ${feeWalletAddress(network)}")
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
