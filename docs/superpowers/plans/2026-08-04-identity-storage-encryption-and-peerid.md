# Identity Storage Encryption & Real libp2p PeerID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt the identity seed + derived keys at rest with a KeyStore-wrapped AES-GCM key, and derive a real libp2p Ed25519 PeerID that matches the `LibP2PManager` host.

**Architecture:** Add a pure-Kotlin `IdentityBlobCodec` (binary framing) and a `SeedCipher` (AES-GCM with IV-prefixed blob, delegating the raw cipher to an `AesGcmCipher` seam for JVM testability). Rewire `IdentityManager` storage to a single encrypted blob with legacy-plaintext migration. Replace the fabricated PeerID with `unmarshalEd25519PrivateKey` + `PeerId.fromPubKey`.

**Tech Stack:** Kotlin 2.1.0, AndroidKeyStore, javax.crypto (AES/GCM), jvm-libp2p 1.3.5, JUnit 4.

## Global Constraints

- All Gradle commands run from `android/` (`./gradlew ...`); repo root is NOT a Gradle project.
- Plain JUnit 4 only — NO Robolectric. Tests must not touch Android framework classes. Android-bound crypto is exercised via a pure-JVM seam.
- Package root `com.neop2p`; new code under `android/app/src/main/java/com/neop2p/`.
- Do NOT add new Gradle dependencies (jvm-libp2p, javax.crypto, android.security.keystore are already available).
- Do NOT change the BIP-32 derivation paths or the `Identity` data class shape (only how it's stored + PeerID derivation).
- `IdentityManager` is constructed by `AppModule` as `IdentityManager(context)` — do not change its constructor signature or AppModule's provider.
- The BIP-39 mnemonic must remain the recovery/backup path (the seed stays derivable from it).
- Existing `IdentityManagerTest` must continue to pass (its helper logic is updated in Task 4).
- Commit after each task with the given message.

---

### Task 1: `IdentityBlobCodec` — pure binary framing

**Files:**
- Create: `android/app/src/main/java/com/neop2p/data/p2p/IdentityBlobCodec.kt`
- Test: `android/app/src/test/java/com/neop2p/data/p2p/IdentityBlobCodecTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces:
  - `data class IdentityBlob(seedPhrase: List<String>, peerId: String, nostrPubkeyHex: String, nostrPrivateKeyHex: String, nickname: String, lnNodeId: String)`
  - `object IdentityBlobCodec { fun encode(blob: IdentityBlob): ByteArray; fun decode(bytes: ByteArray): IdentityBlob }`

- [ ] **Step 1: Write the failing test**

Create `IdentityBlobCodecTest.kt`:

```kotlin
package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityBlobCodecTest {

    private val sample = IdentityBlob(
        seedPhrase = listOf("abandon", "abandon", "about"),
        peerId = "12D3KooWabc123",
        nostrPubkeyHex = "0f".repeat(32),
        nostrPrivateKeyHex = "ff".repeat(32),
        nickname = "Trader One",
        lnNodeId = "030000000000000000000000000000000000000000000000000000000000000000"
    )

    @Test
    fun `round_trips all fields losslessly`() {
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(sample))
        assertEquals(sample.seedPhrase, decoded.seedPhrase)
        assertEquals(sample.peerId, decoded.peerId)
        assertEquals(sample.nostrPubkeyHex, decoded.nostrPubkeyHex)
        assertEquals(sample.nostrPrivateKeyHex, decoded.nostrPrivateKeyHex)
        assertEquals(sample.nickname, decoded.nickname)
        assertEquals(sample.lnNodeId, decoded.lnNodeId)
    }

    @Test
    fun `empty strings and unicode nickname survive`() {
        val blob = sample.copy(nickname = "Résumé 🧑", lnNodeId = "")
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(blob))
        assertEquals(blob, decoded)
    }

    @Test
    fun `single word seed phrase works`() {
        val blob = sample.copy(seedPhrase = listOf("zoo"))
        val decoded = IdentityBlobCodec.decode(IdentityBlobCodec.encode(blob))
        assertEquals(blob, decoded)
    }

    @Test
    fun `decode rejects truncated input`() {
        val bytes = IdentityBlobCodec.encode(sample)
        assertThrows(IllegalArgumentException::class.java) {
            IdentityBlobCodec.decode(bytes.copyOfRange(0, bytes.size - 5))
        }
    }

    @Test
    fun `decode rejects empty input`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityBlobCodec.decode(ByteArray(0))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.IdentityBlobCodecTest"`
Expected: compile error — `IdentityBlob` / `IdentityBlobCodec` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create `IdentityBlobCodec.kt` using the same length-prefixed framing style as `EnvelopeCodec` (4-byte big-endian int length + bytes):

```kotlin
package com.neop2p.data.p2p

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class IdentityBlob(
    val seedPhrase: List<String>,
    val peerId: String,
    val nostrPubkeyHex: String,
    val nostrPrivateKeyHex: String,
    val nickname: String,
    val lnNodeId: String
)

object IdentityBlobCodec {

    fun encode(blob: IdentityBlob): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, blob.seedPhrase.joinToString(" "))
        writeString(out, blob.peerId)
        writeString(out, blob.nostrPubkeyHex)
        writeString(out, blob.nostrPrivateKeyHex)
        writeString(out, blob.nickname)
        writeString(out, blob.lnNodeId)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): IdentityBlob {
        if (bytes.isEmpty()) throw IllegalArgumentException("Empty identity blob")
        val input = ByteArrayInputStream(bytes)
        try {
            val seedPhrase = readString(input)?.split(" ")?.filter { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Missing seed phrase")
            val peerId = readString(input) ?: throw IllegalArgumentException("Missing peerId")
            val nostrPubkey = readString(input) ?: throw IllegalArgumentException("Missing nostr pubkey")
            val nostrPrivkey = readString(input) ?: throw IllegalArgumentException("Missing nostr privkey")
            val nickname = readString(input) ?: ""
            val lnNodeId = readString(input) ?: ""
            return IdentityBlob(seedPhrase, peerId, nostrPubkey, nostrPrivkey, nickname, lnNodeId)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed identity blob", e)
        }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size ushr 24) and 0xFF)
        out.write((bytes.size ushr 16) and 0xFF)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun readString(input: ByteArrayInputStream): String? {
        if (input.available() < 4) return null
        val len = (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
        if (len < 0 || len > input.available()) return null
        val buf = ByteArray(len)
        if (input.read(buf) != len) return null
        return String(buf, StandardCharsets.UTF_8)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.IdentityBlobCodecTest"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/IdentityBlobCodec.kt android/app/src/test/java/com/neop2p/data/p2p/IdentityBlobCodecTest.kt
git commit -m "feat(identity): add IdentityBlob binary codec"
```

---

### Task 2: `AesGcmCipher` seam + `SeedCipher`

**Files:**
- Create: `android/app/src/main/java/com/neop2p/data/p2p/AesGcmCipher.kt`
- Create: `android/app/src/main/java/com/neop2p/data/p2p/SeedCipher.kt`
- Create: `android/app/src/main/java/com/neop2p/data/p2p/KeyStoreAesGcmCipher.kt`
- Test: `android/app/src/test/java/com/neop2p/data/p2p/SeedCipherTest.kt` (+ a `JvmAesGcmCipher` in the test source set)

**Interfaces:**
- Consumes: `Context` (only `KeyStoreAesGcmCipher`), `java.security`, `javax.crypto`.
- Produces:
  - `interface AesGcmCipher { fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray; fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray }`
  - `class SeedCipher(private val gcm: AesGcmCipher) { fun encrypt(plaintext: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }`
  - `class KeyStoreAesGcmCipher(context: Context) : AesGcmCipher`

- [ ] **Step 1: Write the failing test**

Create `SeedCipherTest.kt` with a JVM AES-GCM implementation of `AesGcmCipher`:

```kotlin
package com.neop2p.data.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class JvmAesGcmCipher(private val key: SecretKey) : AesGcmCipher {
    override fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(plaintext)
    }
    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(ciphertextWithTag)
    }
}

class SeedCipherTest {

    private fun newCipher(): SeedCipher {
        val kg = KeyGenerator.getInstance("AES").apply { init(256) }
        return SeedCipher(JvmAesGcmCipher(kg.generateKey()))
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val cipher = newCipher()
        val plaintext = ByteArray(64) { it.toByte() }
        assertArrayEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext)))
    }

    @Test
    fun `output is iv prefixed to ciphertext`() {
        val cipher = newCipher()
        val plaintext = "secret".toByteArray(Charsets.UTF_8)
        val blob = cipher.encrypt(plaintext)
        // 12-byte random IV prefix, then ciphertext (which for AES-GCM includes the 16-byte tag)
        assert(blob.size >= 12 + 16)
        assertArrayEquals(plaintext, cipher.decrypt(blob))
    }

    @Test
    fun `encrypt uses a fresh random IV each time`() {
        val cipher = newCipher()
        val plaintext = byteArrayOf(1, 2, 3)
        val blob1 = cipher.encrypt(plaintext)
        val blob2 = cipher.encrypt(plaintext)
        // Two encryptions of the same plaintext must differ (fresh IV)
        assert(!blob1.contentEquals(blob2))
    }

    @Test
    fun `decrypt rejects truncated blob`() {
        val cipher = newCipher()
        val blob = cipher.encrypt(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(blob.copyOfRange(0, 5))
        }
    }

    @Test
    fun `decrypt rejects tampered ciphertext`() {
        val cipher = newCipher()
        val blob = cipher.encrypt("data".toByteArray(Charsets.UTF_8))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.SeedCipherTest"`
Expected: compile error — `AesGcmCipher` / `SeedCipher` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create `AesGcmCipher.kt`:

```kotlin
package com.neop2p.data.p2p

interface AesGcmCipher {
    fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray
}
```

Create `SeedCipher.kt`:

```kotlin
package com.neop2p.data.p2p

import java.security.SecureRandom

/**
 * Encrypts/decrypts an identity blob using AES-GCM. The output layout is
 * [iv (12 bytes)][ciphertext || tag], so a single blob round-trips via [decrypt].
 * The raw cipher is delegated to an [AesGcmCipher] so it can be unit-tested on the
 * JVM (AndroidKeyStore is unavailable in plain JUnit).
 */
class SeedCipher(private val gcm: AesGcmCipher) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val ct = gcm.encrypt(plaintext, iv)
        return iv + ct
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 12) throw IllegalArgumentException("Ciphertext blob too short")
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        return gcm.decrypt(iv, ct)
    }
}
```

Create `KeyStoreAesGcmCipher.kt` (mirrors `SqlCipherPassphraseManager`'s KeyStore pattern):

```kotlin
package com.neop2p.data.p2p

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreAesGcmCipher(context: Context) : AesGcmCipher {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val secretKey: SecretKey = if (keyStore.containsAlias(ALIAS)) {
        keyStore.getKey(ALIAS, null) as SecretKey
    } else {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .apply {
                            try { setIsStrongBoxBacked(true) } catch (_: Exception) {}
                        }
                        .build()
                )
            }
            .generateKey()
    }

    override fun encrypt(plaintext: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return c.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return c.doFinal(ciphertextWithTag)
    }

    companion object {
        private const val ALIAS = "neop2p_identity_seed"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.SeedCipherTest"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/AesGcmCipher.kt android/app/src/main/java/com/neop2p/data/p2p/SeedCipher.kt android/app/src/main/java/com/neop2p/data/p2p/KeyStoreAesGcmCipher.kt android/app/src/test/java/com/neop2p/data/p2p/SeedCipherTest.kt
git commit -m "feat(identity): add SeedCipher with KeyStore AES-GCM blob layout"
```

---

### Task 3: Encrypt `IdentityManager` storage + legacy migration

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt`
  - `saveIdentityToStorage()` (currently ~line 446)
  - `loadIdentityFromStorage()` (currently ~line 466)
  - `resetIdentity()` (currently ~line 113)
  - add `import android.util.Base64` and a `seedCipher` field + `migrateLegacyIdentity(...)` helper
- Test: `android/app/src/test/java/com/neop2p/data/p2p/IdentityBlobCodecTest.kt` (already covers framing; storage paths are Android-bound, covered by build)

**Interfaces:**
- Consumes: `IdentityBlob` + `IdentityBlobCodec` (Task 1), `SeedCipher` + `KeyStoreAesGcmCipher` (Task 2).
- Produces: (unchanged public API) `getOrCreateIdentity`, `restoreFromSeedPhrase`, `resetIdentity`, key accessors.

- [ ] **Step 1: (skip) No standalone test — storage is Android-bound (SharedPreferences + Base64 + KeyStore); verified by build + code review. The pure framing is already tested in Task 1.**

- [ ] **Step 2: (skip)**

- [ ] **Step 3: Implement**

Add imports and a `seedCipher` field to `IdentityManager`:

```kotlin
import android.util.Base64
import android.util.Log

// inside the class, add field near cachedIdentity:
private val seedCipher: SeedCipher = SeedCipher(KeyStoreAesGcmCipher(context))
```

Replace `saveIdentityToStorage(...)`:

```kotlin
private fun saveIdentityToStorage(identity: Identity) {
    try {
        val blob = IdentityBlob(
            seedPhrase = identity.seedPhrase,
            peerId = identity.peerId,
            nostrPubkeyHex = identity.nostrPubkeyHex,
            nostrPrivateKeyHex = identity.nostrPrivateKeyHex,
            nickname = identity.nickname,
            lnNodeId = identity.lnNodeId
        )
        val encrypted = seedCipher.encrypt(IdentityBlobCodec.encode(blob))
        val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("encrypted_identity", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putInt("identity_version", 2)
            .apply()
        Log.d(TAG, "Identity saved to encrypted storage")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save identity", e)
    }
}
```

Replace `loadIdentityFromStorage(...)`:

```kotlin
private fun loadIdentityFromStorage(): Identity? {
    try {
        val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString("encrypted_identity", null)
        if (encryptedB64 != null) {
            val bytes = seedCipher.decrypt(Base64.decode(encryptedB64, Base64.NO_WRAP))
            val blob = IdentityBlobCodec.decode(bytes)
            val seed = mnemonicToSeed(blob.seedPhrase)
            val identity = deriveIdentityFromSeed(seed, blob.seedPhrase)
            Log.d(TAG, "Identity loaded from encrypted storage")
            return identity
        }
        // No encrypted blob: try migrating legacy plaintext fields.
        return migrateLegacyIdentity(prefs)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load identity", e)
        return null
    }
}
```

Add the migration helper (private):

```kotlin
private fun migrateLegacyIdentity(prefs: android.content.SharedPreferences): Identity? {
    val seedPhraseStr = prefs.getString("seed_phrase", null) ?: return null
    val seedPhrase = seedPhraseStr.split(" ")
    if (!validateBip39Checksum(seedPhrase)) {
        Log.w(TAG, "Legacy seed phrase fails checksum — proceeding for migration compatibility")
    }
    val seed = mnemonicToSeed(seedPhrase)
    val identity = deriveIdentityFromSeed(seed, seedPhrase)
    saveIdentityToStorage(identity)
    prefs.edit()
        .remove("seed_phrase")
        .remove("peer_id")
        .remove("nostr_pubkey")
        .remove("nostr_privkey")
        .remove("nickname")
        .remove("ln_node_id")
        .apply()
    Log.d(TAG, "Migrated legacy identity to encrypted storage")
    return identity
}
```

Update `resetIdentity()` so it clears the new keys too (keep the existing KeyStore-alias delete):

```kotlin
context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
    .edit().clear().apply()
```

(No change needed to the clear() call — it already wipes all keys including the new ones.)

- [ ] **Step 4: Run build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt
git commit -m "feat(identity): encrypt identity blob at rest with KeyStore-wrapped AES-GCM"
```

---

### Task 4: Real libp2p Ed25519 PeerID

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt`
  - `deriveLibp2pPeerId()` (currently ~line 433) — replace body
  - remove the now-unused `bytesToBase58(...)` helper (currently ~line 530)
- Test: `android/app/src/test/java/com/neop2p/data/p2p/IdentityManagerTest.kt`
  - replace the test's private `deriveLibp2pPeerId(...)` helper with the real libp2p derivation, and remove its `bytesToBase58(...)`

**Interfaces:**
- Consumes: `io.libp2p.crypto.keys.Ed25519Kt.unmarshalEd25519PrivateKey(ByteArray)`, `io.libp2p.core.PeerId.fromPubKey(PubKey)`.
- Produces: `deriveLibp2pPeerId(privateKeySeed: ByteArray): String` returning a real base58 libp2p PeerID.

- [ ] **Step 1: Write the failing test**

In `IdentityManagerTest.kt`, replace the private helper `deriveLibp2pPeerId` (currently lines 130-133) and `bytesToBase58` (lines 135-150) with:

```kotlin
private fun deriveLibp2pPeerId(privateKey: ByteArray): String {
    val priv = io.libp2p.crypto.keys.Ed25519Kt.unmarshalEd25519PrivateKey(privateKey)
    return io.libp2p.core.PeerId.fromPubKey(priv.publicKey()).toBase58()
}
```

Add a test asserting the PeerID is a valid libp2p PeerID (round-trips through `PeerId.fromBase58`):

```kotlin
@Test
fun `derived peer id is a valid libp2p peer id`() {
    val seed = mnemonicToSeed(testMnemonic)
    val libp2pKey = deriveChildKey(seed, "m/44'/888'/0'/0/0")
    val peerId = deriveLibp2pPeerId(libp2pKey)
    // Must round-trip through libp2p's own parser (rejects fabricated strings)
    val parsed = io.libp2p.core.PeerId.fromBase58(peerId)
    assertEquals(peerId, parsed.toBase58())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.IdentityManagerTest"`
Expected: the new `derived peer id is a valid libp2p peer id` test FAILS because the current test helper still fabricates the SHA-256 PeerID (which `PeerId.fromBase58` rejects).

- [ ] **Step 3: Implement**

In `IdentityManager.kt`, replace `deriveLibp2pPeerId(...)`:

```kotlin
private fun deriveLibp2pPeerId(privateKeySeed: ByteArray): String {
    val priv = io.libp2p.crypto.keys.Ed25519Kt.unmarshalEd25519PrivateKey(privateKeySeed)
    return io.libp2p.core.PeerId.fromPubKey(priv.publicKey()).toBase58()
}
```

Remove the now-unused `bytesToBase58(...)` private function entirely. Verify the peerId is consistent with what `LibP2PManager` derives: both use the same 32-byte seed (`getLibp2pPrivateKey()`), so `unmarshalEd25519PrivateKey(seed).publicKey()` yields the host's exact PeerID.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.IdentityManagerTest"`
Expected: all tests PASS (including the new validity test and the existing determinism tests).

Also run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt android/app/src/test/java/com/neop2p/data/p2p/IdentityManagerTest.kt
git commit -m "feat(identity): derive real libp2p Ed25519 PeerID matching the host"
```

---

## Self-Review

**Spec coverage:**
- `SeedCipher` KeyStore-wrapped AES-GCM → Task 2. ✅
- Identity blob serialization + storage → Tasks 1 & 3. ✅
- Legacy plaintext migration → Task 3 (`migrateLegacyIdentity`). ✅
- Real libp2p PeerID via `unmarshalEd25519PrivateKey` + `PeerId.fromPubKey` → Task 4. ✅
- Remove fabricated SHA-256 `bytesToBase58` → Tasks 3 & 4. ✅
- Testability via pure-Kotlin framing (Task 1) + JVM AES-GCM seam (Task 2) → covered. ✅
- Existing `IdentityManagerTest` continues to pass → Task 4 updates its helper to the real derivation. ✅

**Placeholder scan:** No TBD/TODO. Every code step has concrete implementation. The "skip" test steps are explicitly justified (Android-bound, covered by build + adjacent pure tests).

**Type consistency:** `IdentityBlob(seedPhrase: List<String>, peerId, nostrPubkeyHex, nostrPrivateKeyHex, nickname, lnNodeId)` and `IdentityBlobCodec.encode/decode` match across Tasks 1 and 3. `AesGcmCipher.encrypt(plaintext, iv)/decrypt(iv, ct)` and `SeedCipher.encrypt(plaintext)/decrypt(blob)` match across Tasks 2 and 3. `SeedCipher(KeyStoreAesGcmCipher(context))` construction in Task 3 matches the Task 2 constructors. `deriveLibp2pPeerId(ByteArray): String` is consistent between the IdentityManager method and the test helper.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-04-identity-storage-encryption-and-peerid.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — I execute tasks in this session with checkpoints.

Which approach?
