# ============================================================================
# You-Tube — R8 / ProGuard rules
# R8 full mode is enabled (gradle.properties: android.enableR8.fullMode=true)
# ============================================================================

# ------------------------- kotlinx.serialization ---------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.you.tube.**$$serializer { *; }
-keepclassmembers class app.you.tube.** { *** Companion; }
-keepclasseswithmembers class app.you.tube.** { kotlinx.serialization.KSerializer serializer(...); }

# ------------------------- NewPipeExtractor --------------------------------
# The extractor resolves services/renderers reflectively in places and drives
# Rhino (JS engine used for YouTube signature/nSig deciphering) — keep intact.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.schabi.newpipe.extractor.downloader.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.slf4j.**
-dontwarn org.checkerframework.**

# ------------------------- Coroutines debug metadata -----------------------
-dontwarn kotlinx.coroutines.debug.**

# ------------------------- OkHttp / Okio / Conscrypt -----------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
