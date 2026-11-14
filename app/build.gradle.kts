plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lansync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lansync.app"
        minSdk = 29
        targetSdk = 34
        // 固定为本机已安装的 build-tools(AGP 8.13 最低要求 35.0.0),避免从不可达的 dl.google.com 自动下载
        buildToolsVersion = "35.0.0"
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            // R8 蒸馏复启（2026-11-15 二次裁决）：非 R8 多 dex 产物在本机流水线上**运行时不可信**
            // ——classes2.dex 里的类在设备端解析不到（ClassNotFoundException），而本地 dexdump、
            // 内部校验和、dex↔类路径全量比对全部通过：mergeDex 产物问题，应用层无法修。
            // 整场事故中唯一经设备验证「服务器正常启动」的 release 配置就是 R8 + `-dontoptimize`
            // 单 dex 产物。体积收益由 icons-core/依赖剔除保留（数字见 docs/BUILD.md §4）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            // 临时挂 debug 签名（2026-11-08 裁决：正式 keystore 延后）：保证产物可安装，
            // 且与已装的 debug 签名包同签名可覆盖升级。换正式签名后无法覆盖 debug 签名安装，需卸载重装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Compose Compiler 1.5.5 ↔ Kotlin 1.9.20
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/io.netty.versions.properties"
            // 桌面端 ANSI 控制台库（jansi）的 Windows/macOS 原生库，Android 上纯死重
            excludes += "org/fusesource/**"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.animation:animation:1.6.8")
    implementation("androidx.compose.material3:material3")
    // 图标只引 core（49 个经典图标），core 未收录的由 ui/icons/LanSyncIcons.kt 本地提供——
    // icons-extended 携带约 7000 个图标类（仅此一个依赖的 dex 即 ≈30MB），无 R8 下是体积第一大头
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    implementation("org.jmdns:jmdns:3.5.8")

    implementation("io.ktor:ktor-server-core-jvm:2.3.5")
    // CIO 引擎（纯 Kotlin/coroutines）：替代 netty——后者在 R8 下依赖
    // ReflectiveChannelFactory / TypeParameterMatcher 等反射面，屡次以启动闪退或连接 RST
    // 的形式炸在真机上；CIO 无此反射面，dex 也少数 MB。HTTP 契约不变（SPEC §2）。
    implementation("io.ktor:ktor-server-cio:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-server-test-host:2.3.5")
}

// 依赖级体积剔除（2026-11-15，无 R8 体积治理的一部分）：每项都先经字节码级 grep 验证
// 保留依赖对其零引用（epoll/kqueue/unix/http2 与 jansi、kotlin-reflect 全部 0 命中），
// 才允许剔除；新增依赖后要重新验证。仅作用于 release 运行时——MockK 等测试依赖需要
// kotlin-reflect，不能外溢到测试类路径。详见 docs/BUILD.md §4。
configurations.matching { it.name == "releaseRuntimeClasspath" }.all {
    // 注：netty 已随 CIO 引擎切换整体移除（2026-11-15），不再需要任何 netty 相关剔除；
    // 历史教训——剔除 v1.3 时按「未用」剔过 netty 模块（其栈经 codec-http2/native-epoll 声明
    // 传递），整组掉出类路径且 D8 对超类缺失类静默丢 dex，导致启动闪退，见 docs/BUILD.md §4。
    // 桌面端 ANSI 控制台库（经 DevelopmentEngine 等未用路径声明），Android 上零调用
    exclude(group = "org.fusesource.jansi")
    // 全反射库 ≈1.5MB dex：保留依赖的类文件对其零引用（DTO 序列化走编译期生成的 serializer）
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// 三道源码文本门禁读的东西都不在 Test 任务的默认输入里：`DocFactsTest` 读 `docs/**` 与 `.git` 引用，
// `DesignSystemTest` / `LayeringTest` 读 main 源码文本。不显式声明，改文档（或改注释）不会让
// `testDebugUnitTest` 失效，任务被 UP-TO-DATE 跳过 → 门禁"全绿"其实没跑。这里把它们登记成输入。
val gateSources = files(
    rootProject.fileTree("docs"),
    rootProject.fileTree(".git/refs"),
    rootProject.file(".git/packed-refs"),
)
tasks.withType<Test>().configureEach {
    inputs.files(gateSources)
}