package com.lansync.app.data

import java.util.Locale

/**
 * ICU 中文→拼音转写（`android.icu` API 24+，minSdk 29）：`Han-Latin` 出带调拼音，`Latin-ASCII`
 * 折叠为无调字母；多音字取 ICU 默认读音。非汉字字符原样保留，因此数字/emoji 开头自然落入 `#` 段。
 *
 * 独立成文件的原因：顶层单例会让 JVM 单测在加载文件门面类时触碰 `android.icu`（框架 stub 直接
 * NoClassDefFoundError）；纯排序规则 [sortedByAppName] 必须保持 JVM 可加载，Android 耦合隔离在此。
 */
class IcuAppNameKeyer : AppNameKeyer {
    // Transliterator 非线程安全：key() 会被主线程（UI remember）与协调协程（UpdateCoordinator）共用
    private val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")

    override fun key(appName: String): String = synchronized(transliterator) {
        transliterator.transliterate(appName).lowercase(Locale.ROOT)
    }
}

/** 生产组合根共用实例（转写无外部状态，仅 Transliterator 内部互斥）。 */
val DefaultAppNameKeyer: AppNameKeyer = IcuAppNameKeyer()
