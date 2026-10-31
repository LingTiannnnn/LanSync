package com.lansync.app.data

/**
 * 应用名 A–Z 升序（拼音混排）的唯一排序口径，供远程应用页合并列表与同步页
 * 可更新/版本差异列表共用（ARCH §13.1）：
 * - 中文名按拼音转写参与字母序；与英文同首字母时中文在前（「中文A」排「英文A」前）；
 * - 非 a–z 首字母（数字/emoji/其他文字）归 `#` 段排最后，段内按键原序；
 * - 键相同按包名定序，保证跨刷新顺序稳定；同键同包名依赖 `sortedWith` 稳定性。
 */
fun <T> Iterable<T>.sortedByAppName(
    keyer: AppNameKeyer,
    name: (T) -> String,
    pkg: (T) -> String = { "" }
): List<T> = map { SortKey(it, name(it), keyer) }
    .sortedWith(compareBy({ it.bucket }, { it.hanRank }, { it.key }, { pkg(it.item) }))
    .map { it.item }

/** 应用名 → 小写拉丁排序键。生产用 [IcuAppNameKeyer]（独立文件，Android 耦合）；JVM 单测注入映射/恒等 Fake。 */
fun interface AppNameKeyer {
    fun key(appName: String): String
}

/** 单元素装饰键：排序全程只转写一次，避免比较器内重复调 [AppNameKeyer.key]。 */
private class SortKey<T>(val item: T, rawName: String, keyer: AppNameKeyer) {
    val key: String = keyer.key(rawName)
    val bucket: Int = bucketOf(key)
    val hanRank: Int = if (isHanName(rawName)) 0 else 1
}

private const val HASH_BUCKET = 26

private fun bucketOf(key: String): Int {
    val first = key.firstOrNull() ?: return HASH_BUCKET
    return if (first in 'a'..'z') first - 'a' else HASH_BUCKET
}

/** 首字符是否汉字（含扩展区/兼容区）；决定同字母段内「中文先于英文」。 */
private fun isHanName(name: String): Boolean {
    if (name.isEmpty()) return false
    return when (name.codePointAt(0)) {
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, in 0x20000..0x2EBEF -> true
        else -> false
    }
}
