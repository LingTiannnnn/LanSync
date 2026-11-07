# 当前 release 已停用 R8（isMinifyEnabled=false，2026-11-08 裁决），本文件暂不生效；
# 内容保留作为重新启用 R8 时的底线规则与历史依据（v1.2 首发闪退根因见「Ktor & Netty」段注释）。

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
# 反射风险实测：v1.2 首发在启动时闪退——Netty ReflectiveChannelFactory 用
# clazz.getConstructor() 反射实例化 NioServerSocketChannel，R8 看不到直接调用者
# 就把无参构造器裁了。keepclassmembers 只保住「可达类」的成员（反射目标类必然可达），
# 不可达的数千 Netty 类仍会被剪掉——26.1MB→4.2MB 的体积收益来自删 -keep class，
# 这条 members 规则是安全的底线，禁止移除。
-dontwarn io.ktor.**
-dontwarn io.netty.**
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
# Compose AAR 自带 consumer rules，全量 keep 会连 material-icons-extended 的数千个图标类一起保留，禁止回退。
-dontwarn androidx.compose.**

# FileProvider
-keep class androidx.core.content.FileProvider { *; }
