# Easy Share / Netshare — R8 keep rules (release minify + shrinkResources)
# Guidance: proguard-android-optimize.txt + full mode + repackage (Play optimization score)

# Prefer smaller DEX: flatten obfuscated classes into a short package.
# (Play optimization score: package renaming. Avoid empty default package — Room/WorkManager.)
-repackageclasses 'o'

# Unity Ads pulls WorkManager/Room; constructors must survive full-mode + repackage.
-keep class androidx.work.** { *; }
-keep interface androidx.work.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# WebRTC (JNI / native)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Eclipse Paho MQTT (callbacks / reflection)
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep interface org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# Conscrypt reflects checkServerTrusted(chain, authType, host) on the TrustManager.
# Keep the method name/signature only — allow class rename + package move.
-keepclassmembers,allowobfuscation,allowrepackage class * implements javax.net.ssl.X509TrustManager {
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], java.lang.String, java.lang.String);
}

# Tink / security-crypto optional annotations (compile-only deps)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Crash retrace
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Unity Ads
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-dontwarn com.unity3d.ads.**
-dontwarn com.unity3d.services.**

# Play Billing
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**
