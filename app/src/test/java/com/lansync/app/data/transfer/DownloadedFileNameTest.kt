package com.lansync.app.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DownloadedFileName 测试（SPEC.md §5.3 命名 / §5.4 解析）。
 * 收敛后的单一实现，取代旧 AppListClient 与 MainViewModel 的两份重复逻辑（ARCHITECTURE P7）。
 */
class DownloadedFileNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `serverArtifactName split uses apks with underscored package`() {
        assertEquals(
            "com_example_app_123.apks",
            DownloadedFileName.serverArtifactName("com.example.app", 123, isSplitApk = true)
        )
    }

    @Test
    fun `serverArtifactName single uses apk`() {
        assertEquals(
            "com_example_app_123.apk",
            DownloadedFileName.serverArtifactName("com.example.app", 123, isSplitApk = false)
        )
    }

    @Test
    fun `defaultDownloadName with version`() {
        assertEquals("com_a_10.apks", DownloadedFileName.defaultDownloadName("com.a", 10))
    }

    @Test
    fun `defaultDownloadName latest has no version segment`() {
        assertEquals("com_a.apks", DownloadedFileName.defaultDownloadName("com.a", null))
    }

    @Test
    fun `parsePackageName strips version and restores dots (apks)`() {
        assertEquals("com.example.app", DownloadedFileName.parsePackageName("com_example_app_123.apks"))
    }

    @Test
    fun `parsePackageName strips version and restores dots (apk)`() {
        assertEquals("com.example.app", DownloadedFileName.parsePackageName("com_example_app_123.apk"))
    }

    @Test
    fun `parsePackageName without version segment restores dots from whole name`() {
        assertEquals("com.a", DownloadedFileName.parsePackageName("com_a.apks"))
    }

    @Test
    fun `resolveDownloadedFile prefers apks then apk`() {
        val dir = tmp.root
        val apks = File(dir, "com_a_10.apks").apply { writeText("x") }
        val found = DownloadedFileName.resolveDownloadedFile(dir, "com.a", 10)
        assertEquals(apks.absolutePath, found?.absolutePath)
    }

    @Test
    fun `resolveDownloadedFile falls back to apk`() {
        val dir = tmp.root
        val apk = File(dir, "com_a_10.apk").apply { writeText("x") }
        val found = DownloadedFileName.resolveDownloadedFile(dir, "com.a", 10)
        assertEquals(apk.absolutePath, found?.absolutePath)
    }

    @Test
    fun `resolveDownloadedFile returns null when absent`() {
        assertNull(DownloadedFileName.resolveDownloadedFile(tmp.root, "com.none", 1))
    }
}
