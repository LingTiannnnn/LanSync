package com.lansync.app.data

/**
 * 应用名 A–Z 升序（拼音混排）的唯一排序口径，供远程应用页合并列表与同步页
 * 可更新/版本差异列表共用（ARCH §13.1）：
 * - 中文名按拼音转写参与字母序；与英文同首字母时中文在前（「中文A」排「英文A」前）；
 * - 非 a–z 首字母（数字/emoji/其他文字）归 `#` 段排最后，段内按键原序；
 * - 键相同按包名定序，保证跨刷新顺序稳定；同键同包名依赖 `sortedWith` 稳定性。
 *
 * 字母索引条（远程应用页侧边轨道）的段位推导 [appNameIndexSections] 也锚定在本文件：
 * 与排序共用 [sectionLabel] 和同一把 [AppNameKeyer]，轨与排序永不漂移。
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
private const val HASH_LABEL = '#'

/** 索引轨道固定槽数：a–z 26 槽 + `#` 段。 */
private const val RAIL_SLOTS = HASH_BUCKET + 1

/** 排序键 → 索引段标签：首字母 ∈ a–z 取该字母，否则归 `#`。排序分桶与字母索引条共用。 */
private fun sectionLabel(key: String): Char {
    val first = key.firstOrNull() ?: return HASH_LABEL
    return if (first in 'a'..'z') first else HASH_LABEL
}

private fun bucketOf(key: String): Int {
    val label = sectionLabel(key)
    return if (label == HASH_LABEL) HASH_BUCKET else label - 'a'
}

/**
 * 已按 [sortedByAppName] 排序的列表 → 字母索引轨道：固定 [RAIL_SLOTS] 槽
 * （0–25 = a–z，26 = `#`），每槽为该段**首项下标**，缺段为 -1。
 * 段位与排序同源（同一把 [AppNameKeyer]），中文名按拼音首字母落段；
 * 「首现即首项」依赖入参已排序这一前提。远程应用页侧边索引条是唯一调用方。
 */
fun <T> List<T>.appNameIndexSections(
    keyer: AppNameKeyer,
    name: (T) -> String
): List<Int> {
    val sections = MutableList(RAIL_SLOTS) { -1 }
    forEachIndexed { index, item ->
        val slot = bucketOf(keyer.key(name(item)))
        if (sections[slot] == -1) sections[slot] = index
    }
    return sections
}

/**
 * 列表滚动反查：itemIndex 所在的轨道槽位。取 firstIndex ≤ itemIndex 的最后一个非 -1 段；
 * itemIndex 早于首个有段条目时钳到首段，无任何段返回 -1。与 [appNameIndexSections] 配对使用。
 */
fun railPositionFor(sections: List<Int>, itemIndex: Int): Int {
    val firstPresent = sections.indexOfFirst { it != -1 }
    if (firstPresent == -1) return -1
    var slot = firstPresent
    for (i in firstPresent..sections.lastIndex) {
        val first = sections[i]
        if (first != -1 && first <= itemIndex) slot = i
    }
    return slot
}

/** 首字符是否汉字（含扩展区/兼容区）；决定同字母段内「中文先于英文」。 */
private fun isHanName(name: String): Boolean {
    if (name.isEmpty()) return false
    return when (name.codePointAt(0)) {
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, in 0x20000..0x2EBEF -> true
        else -> false
    }
}
