package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour contract every [SecretStore] implementation must satisfy. The fake here
 * is the reference; `:admind`'s `PassphraseSecretStore` carries the same assertions
 * against a real file.
 */
class SecretStoreTest {

    private class FakeSecretStore : SecretStore {
        private var blob: IdentityBlob? = null
        override fun load(): IdentityBlob? = blob
        override fun save(blob: IdentityBlob) {
            this.blob = blob
        }

        override fun clear() {
            blob = null
        }
    }

    private fun blob(peerId: String, nickname: String) = IdentityBlob(
        seedPhrase = List(11) { "abandon" } + "about",
        peerId = peerId,
        nostrPubkeyHex = "aa".repeat(32),
        nostrPrivateKeyHex = "bb".repeat(32),
        nickname = nickname,
        lnNodeId = ""
    )

    @Test
    fun `load returns null before anything is saved`() {
        assertNull(FakeSecretStore().load())
    }

    @Test
    fun `save then load round-trips the blob`() {
        val store = FakeSecretStore()
        val saved = blob("peer-1", "Alice")
        store.save(saved)
        assertEquals(saved, store.load())
    }

    @Test
    fun `save replaces an existing secret`() {
        val store = FakeSecretStore()
        store.save(blob("peer-1", "Alice"))
        val second = blob("peer-2", "Bob")
        store.save(second)
        assertEquals(second, store.load())
    }

    @Test
    fun `clear removes the secret and is idempotent`() {
        val store = FakeSecretStore()
        store.save(blob("peer-1", "Alice"))
        store.clear()
        assertNull(store.load())
        store.clear()
        assertNull(store.load())
    }
}
