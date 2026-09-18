package com.neop2p.data.p2p

interface AesGcmCipher {
    fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray
}
