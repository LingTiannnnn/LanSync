package com.lansync.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构铁律的自动门禁（ARCH §8.3 / §12.3、§3.2）——此前只靠人工 grep 审计。
 *
 * 不用 Konsist：该库不在本机 Gradle 离线缓存内，引入需联网解析依赖；本测试零新依赖，
 * 直接跑在 `testDebugUnitTest` 门禁里，比外挂 lint 更难被绕过。
 */
class LayeringTest {

    private val mainSrc: File = listOf("src/main/java", "app/src/main/java")
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: error("找不到 src/main/java，工作目录=${System.getProperty("user.dir")}")

    private fun ktFilesUnder(relativeDir: String): List<File> =
        File(mainSrc, relativeDir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `data layer must not import ui layer`() {
        val dataFiles = ktFilesUnder("com/lansync/app/data")
        // 扫到 0 个文件时断言会空过，等于门禁失效，故显式兜底
        assertTrue("data 层源文件数为 0，分层检查已失效", dataFiles.isNotEmpty())

        val violations = dataFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val text = line.trim()
                if (text.startsWith("import com.lansync.app.ui.")) {
                    "${file.name}:${index + 1}: $text"
                } else {
                    null
                }
            }
        }

        assertEquals(
            "data/** 出现 ui 层 import（分层倒置，同 L2 类缺陷）：\n" + violations.joinToString("\n"),
            0,
            violations.size
        )
    }

    @Test
    fun `repository facade stays within 300 lines`() {
        val facade = File(mainSrc, "com/lansync/app/data/repository/LanSyncRepository.kt")
        assertTrue("找不到门面文件：${facade.path}", facade.isFile)

        val lines = facade.readLines().size
        assertTrue("LanSyncRepository 门面 $lines 行，超过 300 行硬约束（ARCH §3.2）", lines <= 300)
    }
}
