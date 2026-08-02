# NEO-P2P ProGuard / R8 Rules
# Keep all P2P, crypto, and serialization classes

# ─── Ktor (P2P WebSocket) ────────────────────────────────────
# ─── Signal Protocol ────────────────────────────────────────
-keep class org.signal.** { *; }
-dontwarn org.signal.**

# ─── bitcoinj ────────────────────────────────────────────────
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**
-dontwarn org.bitcoinj.store.**

# ─── Nostr ──────────────────────────────────────────────────
-keep class com.nostr.** { *; }
-dontwarn com.nostr.**

# ─── WebRTC ─────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ─── Kotlin Serialization ───────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# ─── Room ───────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ─── Hilt ───────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ─── Coroutines ─────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── Domain Models ──────────────────────────────────────────
-keep class com.neop2p.domain.model.** { *; }
-keep class com.neop2p.data.local.entity.** { *; }

# ─── Keep our config (fee wallet must survive R8) ───────────
-keep class com.neop2p.NeoP2PConfig { *; }
