package com.neop2p.data.network

import okhttp3.CertificatePinner

/**
 * Certificate pins for all outbound HTTPS hosts (H2, 2026-09-11; extended to
 * the market-price providers, F5, 2026-09-23).
 *
 * The threat model names network observers as adversaries; a MITM could
 * otherwise serve fake funding confirmations or fee estimates, or bias the
 * BTC/IDR quote a seller sees. Each host is pinned to its current leaf AND its
 * own chain's intermediate (rotation-safe). When a leaf rotates, the
 * intermediate pin keeps the app working; update the leaf pin via the openssl
 * procedure in SECURITY_POSTURE.md.
 *
 * Pinning is a hardening layer, not a trust anchor — the app's real trust
 * anchor remains the RNS identity system.
 */
object ExplorerPins {

    /**
     * (host, pin) pairs — the pin is the SPKI SHA-256, base64.
     *
     * Captured 2026-09-11 from each host's live chain. Each host pins its own
     * leaf + its own intermediate (captured as cert #2 of THAT host's
     * -showcerts chain). Note mempool.space is fronted by a Sectigo
     * intermediate, while mempool.emzy.de / blockstream.info use Let's Encrypt
     * intermediates — a shared intermediate pin would break the others.
     */
    fun pinSpecs(): List<Pair<String, String>> = listOf(
        "mempool.space" to "sha256/wV7micOM/PJtIxPpaZBTdQF0JnfIHXSGzrvsu7fzDdQ=",
        "mempool.space" to "sha256/KqkYYX5LYAYP7XGemqzbtPPIA8x7BS/BbOIcAXf3j2k=",
        "mempool.emzy.de" to "sha256/2C6GDR0DCE+bo0QCo2AoSGt+Mh6qoF6bpxzwxHIRa5Q=",
        "mempool.emzy.de" to "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=",
        "blockstream.info" to "sha256/9AZIg3NfujJYTXeqbdna11kiWdkWCw/2/56Ocss5UJo=",
        "blockstream.info" to "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=",
        // Added 2026-09-17 (provider adapters). Captured live with the openssl
        // procedure in SECURITY_POSTURE.md.
        "btcscan.org" to "sha256/MAszHPH71FN9DbZ0SzszM0ouXyIjbzTQKVyTDc9LZfw=",
        "btcscan.org" to "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        "blockchain.info" to "sha256/Z87j23nY+/WSTtsgE/O4ZcDVhevBohFPgPMU6rV2iSw=",
        "blockchain.info" to "sha256/Wec45nQiFwKvHtuHxSAMGkt19k+uPSw9JlEkxhvYPHk=",
        // Added 2026-09-25: full-capability testnet4 mirror (address index
        // included) used when Tor is on — mempool.space's clearnet host does not
        // answer Tor exit traffic. Leaf + its own Google Trust Services WE1
        // intermediate + GTS Root R4 backup (same chain as the market hosts).
        "mempool.bitmixlist.org" to "sha256/HEB81yjew8acjx172352YlF/5mA2kN9RvYk+MQe4nnk=",
        "mempool.bitmixlist.org" to "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        "mempool.bitmixlist.org" to "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
        // Market-price hosts (F5, 2026-09-23): fed into offer pricing, so a
        // MITM could bias a seller's IDR quote. Same shared OkHttp client as
        // the explorers, so they belong in the same pinner. Leaf + Google
        // Trust Services WE1 intermediate + GTS Root R4 backup (rotation-safe).
        "api.coingecko.com" to "sha256/iAyDoBlNkN0ypLQUj2D87aaMTnWdYPwX4GAn3CakxiU=",
        "api.coingecko.com" to "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        "api.coingecko.com" to "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
        "api.coinpaprika.com" to "sha256/HAxbF1qST6Xw0H9f/8BVp3NxUmkMX5eqhx4B07kzr/c=",
        "api.coinpaprika.com" to "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        "api.coinpaprika.com" to "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
    )

    /**
     * Hosts intentionally NOT pinned (decision B, 2026-09-24).
     *
     * `api.github.com` serves a notify-only release-version check
     * (`UpdateChecker`). The app never downloads code from it — the "Download"
     * action opens the release page in the user's browser — so a MITM can at
     * worst lie about a version number, never deliver a payload. Pinning a
     * third-party CDN leaf would add a silent-failure mode (GitHub rotates
     * certs) for no security gain. This set is documentation only; OkHttp's
     * CertificatePinner already leaves unlisted hosts unchecked.
     */
    val UNPINNED_NOTIFY_ONLY_HOSTS: Set<String> = setOf("api.github.com")

    fun pinConfig(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pin) in pinSpecs()) {
            builder.add(host, pin)
        }
        return builder.build()
    }
}
