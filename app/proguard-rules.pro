# =====================================================================================
#  You-Tube — R8 / ProGuard rules
#  Goal: smallest possible DEX + zero reflective breakage.
#  Everything below is a *keep*, so add rules sparingly: each one costs bytes.
# =====================================================================================

# ---- Keep what the framework reflects on -------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers class * extends android.app.Activity { public void *(android.view.View); }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# ---- App entry points ----------------------------------------------------------------
-keep class com.ultra.youtube.app.MainActivity { *; }
-keep class com.ultra.youtube.app.service.PlaybackService { *; }
-keep class com.ultra.youtube.app.App { *; }

# ---- Media3 / ExoPlayer ---------------------------------------------------------------
# ExoPlayer loads Renderers / Extractors / LoadControls / MediaSources reflectively by
# class name in several code paths (e.g. DefaultRenderersFactory extension lookup and
# MediaCodecInfo discovery).
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }
# Our own MediaSource implementations are handed to ExoPlayer as objects, not by name,
# but their generic signatures are read for track-type inference.
-keep class com.ultra.youtube.app.player.** { *; }
-keepclassmembers class com.ultra.youtube.app.player.** { *; }

# ---- kotlinx.serialization ------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ultra.youtube.app.**$$serializer { *; }
-keepclassmembers class com.ultra.youtube.app.** { *** Companion; }
-keepclasseswithmembers class com.ultra.youtube.app.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- OkHttp / Okio --------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coil ------------------------------------------------------------------------------
-dontwarn coil.**

# ---- NewPipeExtractor (optional, reached by reflection) ---------------------------------
# When the optional dependency is linked, its service lookup is fully reflective.
-keep class org.schabi.newpipe.extractor.** { *; }
-keepclassmembers class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.jsoup.**

# ---- Compose / Kotlin --------------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * { @androidx.compose.runtime.Composable <methods>; }
-keepclassmembers class * { @androidx.compose.runtime.Immutable <fields>; }

# ---- Misc --------------------------------------------------------------------------------
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-optimizationpasses 5
-allowaccessmodification
