# ============================================================================
# You-Tube — R8 full mode rules
# ============================================================================

# Keep line numbers for readable crash traces (small size cost, huge debug win).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Kotlinx Serialization (JSON tree API + any future @Serializable DTOs)
# ---------------------------------------------------------------------------
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# NewPipeExtractor + transitive engines (Rhino JS, jsoup, nanojson)
# Rhino interprets player JS: reflection-heavy, must not be touched.
# ---------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keepclassmembers class * implements org.mozilla.javascript.Scriptable { public *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn org.jsoup.**
-dontwarn javax.tools.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# OkHttp / Okio (platform probes are optional at runtime)
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Guava (pulled by Media3) — strip optional warning-only references
# ---------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.lang.model.**

# ---------------------------------------------------------------------------
# Media3 / Coil / Compose ship consumer rules — nothing extra needed.
# ---------------------------------------------------------------------------
