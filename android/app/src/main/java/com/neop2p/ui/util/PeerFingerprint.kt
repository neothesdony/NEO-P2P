package com.neop2p.ui.util

import android.content.Context
import com.neop2p.R
import java.security.MessageDigest

/**
 * 8-word peer fingerprint derived from a peerId (SHA-256 → BIP-39 wordlist
 * indices). The trust anchor for TOFU key verification: both parties compare
 * the words out-of-band (phone/WA) to detect a relay-level MITM on first
 * contact. Pure + deterministic + JVM-testable (wordlist injected).
 */
object PeerFingerprint {

    /**
     * Deterministic 8-word fingerprint for [peerId] using [wordList] (the
     * BIP-39 English wordlist — same vocabulary as the seed backup).
     * SHA-256 the peerId, take 8 × 11-bit chunks (88 bits) as indices.
     */
    fun words(peerId: String, wordList: List<String>): List<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(peerId.toByteArray(Charsets.UTF_8))
        val bits = digest.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
        return (0 until 8).map { i ->
            val start = i * 11
            val idx = bits.substring(start, start + 11).toInt(2)
            wordList.getOrElse(idx) { "word$idx" }
        }
    }

    /** Compact display form: "word1 word2 … word8". */
    fun display(peerId: String, wordList: List<String>): String =
        words(peerId, wordList).joinToString(" ")

    /** Load the canonical BIP-39 English wordlist from res/raw. */
    fun loadWordList(context: Context): List<String> {
        // Use the compile-time R.raw reference (not getIdentifier) so the resource
        // survives resource-name obfuscation in minified release builds.
        return runCatching {
            context.resources.openRawResource(R.raw.bip39_english)
                .bufferedReader()
                .readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
