# kotlinx.serialization keeps its own generated serializers; these are the usual safe additions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.walkbuddy.**$$serializer { *; }
-keepclassmembers class com.walkbuddy.** { *** Companion; }
-keepclasseswithmembers class com.walkbuddy.** { kotlinx.serialization.KSerializer serializer(...); }
# WebRTC (JNI looks classes up by name)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# Loaded by name when built with -PhealthConnect=true
-keep class com.walkbuddy.health.** { *; }
