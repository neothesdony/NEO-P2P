package com.neop2p.data.local

import android.content.Context
import com.neop2p.domain.model.PaymentDetails
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved payment methods (bank / QRIS / e-wallet) reused across offers.
 *
 * Deliberately SharedPreferences + JSON, NOT Room: the shape mirrors the
 * per-offer `payment_details` JSON exactly, it is a small blob, and it avoids
 * a DB migration (Room stays v21). The seller types their BCA number + holder
 * name once here, then CreateOffer prefills it for every new offer — the
 * Peach "add payment method before your first trade" pattern.
 *
 * Storage format: {"bca":{"accountNumber":"...","accountHolder":"..."},...}
 * — identical to the offer-row payment_details wire format.
 */
@Singleton
class SavedPaymentMethodsStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All saved methods, keyed by method id (bca / qris / gopay / …). */
    fun all(): Map<String, PaymentDetails> = parse(prefs.getString(KEY, "{}").orEmpty())

    fun get(methodId: String): PaymentDetails? = all()[methodId]

    fun save(methodId: String, details: PaymentDetails) {
        val updated = all() + (methodId to details)
        prefs.edit().putString(KEY, toJson(updated)).apply()
    }

    fun remove(methodId: String) {
        val updated = all() - methodId
        prefs.edit().putString(KEY, toJson(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "saved_payment_methods"
        private const val KEY = "methods"

        fun toJson(details: Map<String, PaymentDetails>): String {
            val root = JSONObject()
            details.forEach { (method, d) ->
                root.put(
                    method,
                    JSONObject()
                        .put("accountNumber", d.accountNumber)
                        .put("accountHolder", d.accountHolder)
                        .put("qrisString", d.qrisString)
                )
            }
            return root.toString()
        }

        fun parse(json: String): Map<String, PaymentDetails> {
            return try {
                val root = JSONObject(json)
                val out = mutableMapOf<String, PaymentDetails>()
                root.keys().forEach { method ->
                    val m = root.optJSONObject(method) ?: return@forEach
                    out[method] = PaymentDetails(
                        accountNumber = m.optString("accountNumber"),
                        accountHolder = m.optString("accountHolder"),
                        qrisString = m.optString("qrisString")
                    )
                }
                out
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
