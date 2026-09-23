package com.neop2p.data.portability

import kotlinx.serialization.json.Json

/**
 * JSON codec for [IdentityBundle]. Fails closed on an unknown version or
 * malformed input so a corrupt/foreign bundle can never partially apply.
 */
object BundleCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun encode(bundle: IdentityBundle): String = json.encodeToString(IdentityBundle.serializer(), bundle)

    fun decode(text: String): IdentityBundle {
        val bundle = try {
            json.decodeFromString(IdentityBundle.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed identity bundle", e)
        }
        require(bundle.version == IdentityBundle.CURRENT_VERSION) {
            "Unsupported identity bundle version ${bundle.version}"
        }
        require(bundle.mnemonic.size == 12) { "Identity bundle must carry a 12-word mnemonic" }
        return bundle
    }
}
