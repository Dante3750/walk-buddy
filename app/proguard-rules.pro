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

# Room: the generated database and DAO implementations are found by name; entities are read through generated code.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**
# WorkManager creates workers by class name
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
# The home-screen widget module is reached by component name
-keep class com.walkbuddy.widget.** { *; }
# Receivers and services named in the manifest are kept by the build; these are loaded by name from code as well
-keep class com.walkbuddy.notify.ReminderActionReceiver { *; }
-keep class com.walkbuddy.notify.BootReceiver { *; }
# Android 16 live updates are reached by reflection
-keepclassmembers class android.app.Notification$ProgressStyle { *; }
-dontwarn android.app.Notification$ProgressStyle
# Kotlin metadata for kotlinx.serialization routes in Navigation
-keepclassmembers class **$$serializer { *; }
