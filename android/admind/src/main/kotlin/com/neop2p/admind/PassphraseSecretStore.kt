package com.neop2p.admind

import com.neop2p.data.p2p.IdentityBlob
import com.neop2p.data.p2p.IdentityBlobCodec
import com.neop2p.data.p2p.SecretStore
import com.neop2p.data.p2p.SeedCipher
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.SecretKey

/** Raised when the store is missing, malformed, or the passphrase is wrong. */
class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Argon2id work factors. Production uses [DEFAULT]; tests use [TEST] to stay fast. */
data class Argon2Params(
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int
) {
    companion object {
        val DEFAULT = Argon2Params(memoryKb = 64 * 1024, iterations = 3, parallelism = 1)
        val TEST = Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)
    }
}

/**
 * A [SecretStore] backed by a single passphrase-encrypted file.
 *
 * Layout: `magic "NPA1" (4) | salt (16) | iv (12) | ciphertext || tag`. The AES-256-GCM
 * key is Argon2id(passphrase, salt); the blob framing and payload reuse `:core`'s
 * [SeedCipher] + [IdentityBlobCodec] so the daemon and app share one format.
 *
 * The file is created owner-only (0600) where the filesystem supports it, and writes go
 * through a sibling temp file so a crash cannot leave a torn secret.
 */
class PassphraseSecretStore(
    private val file: Path,
    passphrase: CharArray,
    private val params: Argon2Params = Argon2Params.DEFAULT
) : SecretStore {

    private val passphrase: CharArray = passphrase.copyOf()

    override fun load(): IdentityBlob? {
        if (!Files.exists(file)) return null

        val bytes = Files.readAllBytes(file)
        if (bytes.size < HEADER_BYTES + MIN_PAYLOAD_BYTES || !hasMagic(bytes)) {
            throw SecretStoreException("Not a NEO-P2P secret file: $file")
        }

        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val payload = bytes.copyOfRange(MAGIC.size + SALT_BYTES, bytes.size)

        val plaintext = try {
            SeedCipher(JdkAesGcmCipher(deriveKey(salt))).decrypt(payload)
        } catch (e: Exception) {
            throw SecretStoreException("Wrong passphrase or corrupted secret file: $file", e)
        }

        return try {
            IdentityBlobCodec.decode(plaintext)
        } catch (e: IllegalArgumentException) {
            throw SecretStoreException("Corrupted identity payload in $file", e)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(blob: IdentityBlob) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val plaintext = IdentityBlobCodec.encode(blob)
        val encrypted = try {
            SeedCipher(JdkAesGcmCipher(deriveKey(salt))).encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }

        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        createOwnerOnly(tmp)
        Files.write(tmp, MAGIC + salt + encrypted, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)

        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun clear() {
        Files.deleteIfExists(file)
    }

    private fun createOwnerOnly(path: Path) {
        try {
            Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
        } catch (e: UnsupportedOperationException) {
            Files.createFile(path)
        } catch (e: FileAlreadyExistsException) {
            Files.delete(path)
            createOwnerOnly(path)
        }
    }

    private fun hasMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }

    private fun deriveKey(salt: ByteArray): SecretKey {
        val parameters = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
        )
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKb)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator().apply { init(parameters) }
        val keyBytes = ByteArray(KEY_BYTES)
        generator.generateBytes(passphraseBytes(), keyBytes)
        return javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
    }

    // Encodes the passphrase without materializing it as a String.
    private fun passphraseBytes(): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        private val MAGIC = "NPA1".toByteArray(StandardCharsets.US_ASCII)
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 32
        private const val HEADER_BYTES = 4 + SALT_BYTES

        /** [SeedCipher] framing: 12-byte IV followed by ciphertext + 16-byte tag. */
        private const val MIN_PAYLOAD_BYTES = 12 + 16
    }
}
