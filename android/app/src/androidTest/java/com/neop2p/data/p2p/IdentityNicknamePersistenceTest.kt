package com.neop2p.data.p2p

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neop2p.data.local.EncryptedPrefsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device/emulator. Touches the shared `neop2p_identity` prefs and the
 * `neop2p_identity_seed` KeyStore alias, so run it on a dedicated emulator.
 * A device with a screen lock may gate the seed key (auth window 300s); if the
 * test throws UserNotAuthenticatedException, unlock the device and retry.
 */
@RunWith(AndroidJUnit4::class)
class IdentityNicknamePersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manager() = IdentityManager(context, EncryptedPrefsStore(context))

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @After
    fun cleanUp() {
        runCatching { manager().resetIdentity() }
    }

    @Test
    fun nicknameSurvivesColdReload() {
        manager().getOrCreateIdentity()
        manager().updateNickname("Trader One")

        // A second manager has an empty in-memory cache, so it must read the
        // persisted blob — exactly the cold-start path an update triggers.
        assertEquals("Trader One", manager().getOrCreateIdentity().nickname)
    }

    @Test
    fun restoreFromSeedPhraseCarriesNickname() {
        val restored = manager().restoreFromSeedPhrase(testMnemonic, force = true, nickname = "Imported")
        assertEquals("Imported", restored.nickname)
        assertEquals("Imported", manager().getOrCreateIdentity().nickname)
    }
}
