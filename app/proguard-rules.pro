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

# JmDNS
-keep class javax.jmdns.** { *; }

# Ktor & Netty
# 只放行 dontwarn，不做全量 keep：全量 keep 会把 Netty 数千个类原样保留（release 26MB→4MB 的分差就在这）。
# Netty 在 R8 下的运行时风险点（反射/Unsafe）需真机冒烟把关，见 docs/ROADMAP.md 验收缺口。
-dontwarn io.ktor.**
-dontwarn io.netty.**
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
# Compose AAR 自带 consumer rules，全量 keep 会连 material-icons-extended 的数千个图标类一起保留，禁止回退。
-dontwarn androidx.compose.**

# FileProvider
-keep class androidx.core.content.FileProvider { *; }
