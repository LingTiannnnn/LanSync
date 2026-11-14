# R8 蒸馏已启用（2026-11-15 二次裁决，isMinifyEnabled=true + -dontoptimize 单 dex）：
# 非 R8 多 dex 产物的 mergeDex 结果在设备端不可信（类在 dex 里却 ClassNotFoundException），
# R8 单 dex 是唯一经设备验证可正常启动服务器的 release 形态。改任何 keep 规则后必须
# 真机全链路冒烟再发版。

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
# ⛔ 必须保留泛型签名：netty 的 MessageToMessageDecoder/Encoder 构造函数用
# TypeParameterMatcher 泛型反射（读 getGenericSuperclass() 找类型参数），
# 缺 Signature 属性会在每个新连接装配 HttpServerCodec 时抛
# IllegalStateException: unknown type parameter 'I' → 通道立即关闭 → 客户端 Connection reset。
-keepattributes Signature, EnclosingMethod
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
# v1.2 首发闪退根因（mapping.txt 反查留档）：当时 netty 零 keep 规则，ReflectiveChannelFactory
# 用 clazz.getConstructor() 反射实例化 NioServerSocketChannel，构造器没有直接调用者被 R8 裁掉
# → 启动建服务器即抛 NoSuchMethodException。v1.3 连接被 RST 的根因（slf4j 桥抓到的
# exceptionCaught）：泛型签名缺失，见上「Signature」注释。修复分两层：
# 1. -keep 钉住 Channel 实现的类身份 + 无参构造器（根因修复）：keepclassmembers 挡不住 R8
#    横向类合并（合并会连类带构造器一起消掉），必须 -keep；
# 2. members 底线规则保住可达 Netty 类的全部成员，覆盖其余反射路径。
# 不可达的数千 Netty 类仍会被剪掉——体积收益来自删 -keep class io.netty.**（全量 keep 26.1MB
# vs 两层规则 5.2MB），禁止回退全量 keep；以上两层规则也禁止移除。
-keep class * extends io.netty.channel.Channel {
    public <init>();
}
# TypeParameterMatcher 反射源（v1.3 连接 RST 根因）：MessageToMessageEncoder/Decoder 的
# 构造函数调 TypeParameterMatcher.find(this, Xxx.class, "I")，要求 Xxx.class.getTypeParameters()
# 返回 <I>——即这两个类的泛型签名必须进 dex。R8 的 -keepattributes 只对被 -keep 命中的类生效，
# 单纯写 -keepattributes Signature 无效；必须 -keep 这两个类。
-keep class io.netty.handler.codec.MessageToMessageEncoder { *; }
-keep class io.netty.handler.codec.MessageToMessageDecoder { *; }
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

# SLF4J 自建绑定：slf4j 以 Class.forName("org.slf4j.impl.StaticLoggerBinder") 发现，
# 类名不可混淆；把 netty/ktor 日志转发进 FileLogger（见 org/slf4j/impl/StaticLoggerBinder.kt）
-keep class org.slf4j.impl.StaticLoggerBinder { *; }

# Application entry points
-keep class com.lansync.app.LanSyncApplication { *; }
-keep class com.lansync.app.MainActivity { *; }

# AndroidX Compose
# Compose AAR 自带 consumer rules，全量 keep 会连 material-icons-extended 的数千个图标类一起保留，禁止回退。
-dontwarn androidx.compose.**

# FileProvider
-keep class androidx.core.content.FileProvider { *; }
