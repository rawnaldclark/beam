# ProGuard / R8 rules for Beam (ZapTransfer Android app).
#
# These rules supplement the defaults from proguard-android-optimize.txt.
# Keep rules are ADDITIVE — the optimizer can still remove everything not
# explicitly kept by these rules + the default rules.

# ── Room ─────────────────────────────────────────────────────────────────────
# Entities and DAOs are accessed via reflection by Room's generated code.
-keep class com.zaptransfer.android.data.db.** { *; }

# ── Hilt ─────────────────────────────────────────────────────────────────────
# Generated Hilt components — R8 can inline them; keep just the injection points.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ── lazysodium-android ───────────────────────────────────────────────────────
# JNA maps to native libsodium.so via reflection — keep all JNA interfaces.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }

# ── stream-webrtc-android ────────────────────────────────────────────────────
# WebRTC JNI callbacks are looked up by name.
-keep class org.webrtc.** { *; }

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── ML Kit ────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }

# ── Kotlin serialisation / reflection ─────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── General Android ───────────────────────────────────────────────────────────
-keepclassmembers class * extends android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
