package com.neop2p.data.p2p

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure-JVM BIP-39 mnemonic handling: wordlist, mnemonic generation, checksum
 * validation and PBKDF2 seed stretching.
 *
 * Extracted from [com.neop2p.data.p2p.IdentityManager] so the headless
 * arbitrator daemon (`:admind`) can stretch the admin's mnemonic with no
 * Android `Context`. The 2048-word English wordlist ships as a `:core`
 * classpath resource (`/bip39_english.txt`).
 */
object Bip39 {

    // Standard BIP-39 English wordlist — 2048 words
    // Using the well-known list at https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
    private val cachedWordlist: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/bip39_english.txt")
            ?: error("bip39_english.txt missing from the :core classpath")
        stream.bufferedReader().use { it.readLines().filter { line -> line.isNotBlank() } }
    }

    /** The canonical 2048-word BIP-39 English wordlist. */
    fun wordlist(): List<String> = cachedWordlist

    /**
     * Generate a proper BIP-39 mnemonic (128-bit entropy + 4-bit checksum = 12 words).
     */
    fun generateMnemonic(): List<String> {
        val entropy = ByteArray(16)  // 128 bits = 12 words
        SecureRandom().nextBytes(entropy)

        // Compute checksum: SHA-256 of entropy, take first 4 bits
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = (hash[0].toInt() and 0xFF) shr 4  // Upper 4 bits

        // Combine entropy + checksum bits into 11-bit groups
        val bits = ByteArray(16 + 1)  // 128 bits entropy + 4 bits checksum
        System.arraycopy(entropy, 0, bits, 0, 16)
        bits[16] = (checksumBits shl 4).toByte()

        // Map 11-bit groups to words (12 words = 132 bits)
        val words = mutableListOf<String>()
        var bitBuffer = 0
        var bitCount = 0
        val allBits = bits.flatMap { byte ->
            (7 downTo 0).map { ((byte.toInt() shr it) and 1).toByte() }
        }

        for (i in allBits.indices) {
            bitBuffer = (bitBuffer shl 1) or (allBits[i].toInt() and 1)
            bitCount++
            if (bitCount == 11) {
                words.add(cachedWordlist[bitBuffer])
                bitBuffer = 0
                bitCount = 0
            }
        }

        return words
    }

    /**
     * Validates BIP-39 checksum.
     */
    fun validateChecksum(words: List<String>): Boolean {
        if (words.size != 12) return false
        // Decode words to 11-bit indices, concatenate to bit stream
        val bits = StringBuilder()
        for (word in words) {
            val idx = cachedWordlist.indexOf(word.lowercase())
            if (idx < 0) return false
            // Convert the 0..2047 index to an 11-bit binary string, left-padded
            // with zeros. (String.format("%011d") would emit DECIMAL digits,
            // which then fail to parse as binary below.)
            bits.append(Integer.toBinaryString(idx).padStart(11, '0'))
        }

        // Last 4 bits are checksum
        val checksumBits = bits.takeLast(4).toString()
        val entropyBits = bits.dropLast(4).toString()

        // Convert entropy bits to bytes
        val entropy = ByteArray(16)
        for (i in 0 until 16) {
            val byteStr = entropyBits.substring(i * 8, (i + 1) * 8)
            entropy[i] = byteStr.toInt(2).toByte()
        }

        // Verify checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = (hash[0].toInt() and 0xFF) shr 4
        val actualChecksum = checksumBits.toInt(2)
        return expectedChecksum == actualChecksum
    }

    /**
     * Convert BIP-39 mnemonic to seed using PBKDF2.
     */
    fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = words.joinToString(" ")
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)

        // PBKDF2 with HMAC-SHA512, 2048 iterations
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(
            mnemonic.toCharArray(),
            salt,
            2048,
            512
        )
        return factory.generateSecret(spec).encoded
    }
}
