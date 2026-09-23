# NEO-P2P ProGuard / R8 Rules
# Keep all P2P, crypto, and serialization classes

# ─── Ktor (P2P WebSocket) ────────────────────────────────────
# ─── E2EE (XChaCha20 via Bouncy Castle) ─────────────────────
# libsignal-protocol-java removed (P0-2); org.signal.** rules no longer apply.

# ─── bitcoinj ────────────────────────────────────────────────
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**
-dontwarn org.bitcoinj.store.**

# ─── Kotlin Serialization ───────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# ─── msgpack-core (LXMF announce appData) ───────────────────
# msgpack picks its buffer implementation via reflection; R8 strips the
# unused MessageBufferU variant, causing NoClassDefFoundError at runtime.
-keep class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# ─── RNS/LXMF fork (rns-core + lxmf-core) ────────────────────
# The fork dispatches links via REFLECTION (Transport.getLinkId /
# getInitiator / receive / getAttachedInterfaceHash / validateProof —
# Transport.kt:1185,1319,3241,3368,4142,4152,4391,4409,4472). R8
# obfuscates the Link class method names, every getMethod() throws,
# registerLink() silently no-ops, and ALL link data (offer_request,
# escrow_status, chat) is dropped. Debug builds work (no R8); release
# builds were broken until these rules existed. Keep the whole fork
# un-obfuscated — it is reflection-heavy by design.
-keep class network.reticulum.** { *; }
-dontwarn network.reticulum.**

# ─── Room ───────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ─── Hilt ───────────────────────────────────────────────────
# dagger.hilt.** and javax.inject.** are covered by the consumer ProGuard rules
# Hilt ships in its AAR (META-INF/proguard/). Only the FragmentContextWrapper
# subclass needs an explicit keep (F5, 2026-09-23).
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ─── Coroutines ─────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── Domain Models ──────────────────────────────────────────
-keep class com.neop2p.domain.model.** { *; }
-keep class com.neop2p.data.local.entity.** { *; }

# ─── Keep our config (fee wallet must survive R8) ───────────
-keep class com.neop2p.NeoP2PConfig { *; }

# ─── Strip debug logs from release (F5, 2026-09-23) ──────────
# Decision 3: no crash/telemetry SDK; release logs would only leak peer ids,
# escrow ids, and wallet state into logcat. R8 removes the calls entirely
# under proguard-android-optimize.txt. w/e/wtf are kept for on-device crash
# diagnosis (decision 3 forbids upload, not local logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
