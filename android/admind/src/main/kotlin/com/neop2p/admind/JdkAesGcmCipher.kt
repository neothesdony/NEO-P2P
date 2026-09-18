package com.neop2p.admind

import com.neop2p.data.p2p.AesGcmCipher
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JCE AES-GCM (128-bit tag), mirroring the app's AndroidKeyStore-backed cipher. */
class JdkAesGcmCipher(private val key: SecretKey) : AesGcmCipher {

    override fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(plaintext)
        }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(ciphertextWithTag)
        }
}
