package com.lansync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [sortedByAppName] 排序口径 JVM 单测：全拼升序、同字母中文先于英文、`#` 段殿后、包名定序、空名归 `#`。
 * keyer 用映射 Fake——ICU 转写本体耦合 Android（真机验收），此处只验证纯排序规则。
 */
class AppNameOrderingTest {

    private val keyer = AppNameKeyer { name ->
        mapOf(
            "阿里云盘" to "aliyunpan",
            "爱奇艺" to "aiqiyi",
            "安兔兔" to "antutu",
            "微信" to "weixin",
            "360卫士" to "360weishi"
        )[name] ?: name.lowercase()
    }

    private fun sort(names: List<String>) = names.sortedByAppName(keyer, { it }, { it })

    @Test
    fun `chinese sorted by full pinyin ascending`() {
        // ai < al < an < w：全拼逐字母比较，不是只按首字母分组
        assertEquals(listOf("爱奇艺", "阿里云盘", "安兔兔", "微信"), sort(listOf("微信", "阿里云盘", "安兔兔", "爱奇艺")))
    }

    @Test
    fun `chinese before english within same initial letter`() {
        assertEquals(listOf("阿里云盘", "安兔兔", "Alipay"), sort(listOf("Alipay", "安兔兔", "阿里云盘")))
    }

    @Test
    fun `non letter initials go last regardless of han rank`() {
        assertEquals(listOf("微信", "360卫士", "🎮游戏"), sort(listOf("🎮游戏", "360卫士", "微信")))
    }

    @Test
    fun `same name falls back to package name for stability`() {
        data class Item(val n: String, val p: String)
        val sorted = listOf(Item("App", "com.b"), Item("App", "com.a")).sortedByAppName(keyer, { it.n }, { it.p })
        assertEquals("com.a", sorted[0].p)
    }

    @Test
    fun `empty name sorts into hash bucket`() {
        assertEquals(listOf("微信", ""), sort(listOf("", "微信")))
    }
}
