# NewPipe executes YouTube's JS challenge through Rhino; reflection is required.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.tools.**
# Optional JVM-only TLS providers referenced by OkHttp, not used on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
# Media3, serialization, Coil and AndroidX provide their own consumer rules.
# Deliberately no blanket keep of app/Compose/Media3, and no -ignorewarnings.
