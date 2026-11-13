# Keep Retrofit's reflective service interface and our serialization models.
# (Retrofit/OkHttp/kotlinx-serialization ship consumer rules; these cover our code.)
-keep interface com.hans.aura.data.JellyfinApi { *; }
-keep class com.hans.aura.data.** { *; }

# kotlinx.serialization generated serializers
-keep,includedescriptorclasses class com.hans.aura.**$$serializer { *; }
-keepclassmembers class com.hans.aura.** {
    *** Companion;
}
-keepclasseswithmembers class com.hans.aura.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3 session AIDL/callbacks are reflection-sensitive
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
