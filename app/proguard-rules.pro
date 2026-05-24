# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lansync.app.data.model.**$$serializer { *; }
-keepclassmembers class com.lansync.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.lansync.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# JmDNS
-keep class javax.jmdns.** { *; }

# Ktor & Netty
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }
-dontwarn io.netty.handler.**
-dontwarn io.netty.util.internal.**
-dontwarn io.netty.channel.epoll.**
-dontwarn reactor.blockhound.**
-dontwarn org.slf4j.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep data model classes used in serialization
-keep class com.lansync.app.data.model.** { *; }

# Application entry points
-keep class com.lansync.app.LanSyncApplication { *; }
-keep class com.lansync.app.MainActivity { *; }

# AndroidX Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# FileProvider
-keep class androidx.core.content.FileProvider { *; }
