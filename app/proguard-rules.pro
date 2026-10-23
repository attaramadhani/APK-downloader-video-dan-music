# ===================================================================
# ANYDROP SECURITY & ANTI-DECOMPILATION PROGUARD/R8 RULES
# ===================================================================

# 1. OPTIMIZATION & OBFUSCATION (Acak kode agar tidak bisa dibaca decompiler)
-optimizationpasses 5
-repackageclasses 'com.downloader.allinone.core'
-allowaccessmodification

# 2. HAPUS SEMUA LOGCAT PADA PRODUCTION / RELEASE (Mencegah kebocoran URL dan token)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# 3. PERTAHANKAN MODEL DATA & KOMPONEN ANDROID
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Model Data (JSON serialization / deserialization)
-keep class com.downloader.allinone.model.** { *; }

# Services, Activities, dan Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# 4. JETPACK COMPOSE COMPATIBILITY
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 5. OKHTTP & KOTLIN COROUTINES
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class kotlinx.coroutines.** { *; }

# 6. NEWPIPE EXTRACTOR & RHINO
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**

# 7. WEBVIEW JAVASCRIPT BRIDGE (PENTING!)
# Tanpa aturan ini R8 mengganti nama method @JavascriptInterface di build release,
# sehingga sniffer video (window.AnyDropSniffer.onVideoDetected) diam-diam tidak berfungsi.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 8. CRONET
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# 9. YT-DLP (youtubedl-android) + dependensinya
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# 10. JSOUP
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
