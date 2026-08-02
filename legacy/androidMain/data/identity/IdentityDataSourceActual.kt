package com.neop2p.data.identity

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferenceKey
import androidx.datastore.preferences.preferencesDataStore
import com.neop2p.domain.model.Identity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class IdentityDataSourceActual @Inject constructor(
    private val context: Context
) {

    private val Context.preferencesDataStore by preferencesDataStore(name = "identity_prefs")

    private val IDENTITY_KEY = stringSetPreferenceKey("identity_key")

    override fun getIdentity(): Flow<Identity?> = context.preferencesDataStore.data
        .map { prefs ->
            val identitySet = prefs[IDENTITY_KEY]
            if (identitySet == null || identitySet.isEmpty()) {
                null
            } else {
                // Assuming we stored as: peerId, nickname, nostrPubkeyHex, lnNodeId, createdAt, updatedAt
                val iterator = identitySet.iterator()
                val peerId = iterator.next()
                val nickname = iterator.next()
                val nostrPubkeyHex = iterator.next()
                val lnNodeId = iterator.next()
                val createdAt = iterator.next().toLong()
                val updatedAt = iterator.next().toLong()
                Identity(
                    peerId = peerId,
                    nickname = nickname,
                    nostrPubkeyHex = nostrPubkeyHex,
                    lnNodeId = lnNodeId,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                )
            }
        }

    override fun saveIdentity(identity: Identity) = context.preferencesDataStore.update { prefs ->
        prefs[IDENTITY_KEY] = setOf(
            identity.peerId,
            identity.nickname,
            identity.nostrPubkeyHex,
            identity.lnNodeId,
            identity.createdAt.toString(),
            identity.updatedAt.toString()
        )
    }
}