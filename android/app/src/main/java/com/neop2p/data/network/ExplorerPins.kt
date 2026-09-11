package com.neop2p.data.network

import okhttp3.CertificatePinner

/**
 * Certificate pins for the chain explorers (H2, 2026-09-11).
 *
 * The threat model names network observers as adversaries; a MITM could
 * otherwise serve fake funding confirmations or fee estimates. Each host is
 * pinned to its current leaf AND its own chain's intermediate (rotation-safe).
 * When a leaf rotates, the intermediate pin keeps the app working; update the
 * leaf pin via the openssl procedure in SECURITY_POSTURE.md.
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
    )

    fun pinConfig(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pin) in pinSpecs()) {
            builder.add(host, pin)
        }
        return builder.build()
    }
}
