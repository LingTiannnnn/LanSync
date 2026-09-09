package com.lansync.app.data.transfer

import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/**
 * 新 `data.transfer.AppPacker` 测试（SPEC §5.3 命名/结构、§8.1/§8.3 D1 哈希一致性）。
 *
 * 因 AppPacker 输出目录构造注入，可用 [TemporaryFolder] 造真实源文件做端到端打包验证（无 Android 依赖）。
 */
class AppPackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun app(pkg: String, vc: Long, split: Boolean, paths: List<String>, extractable: Boolean = true) =
        AppInfo(pkg, pkg, "1.0", vc, paths, "listmd5", extractable, 1L, isSplitApk = split)

    @Test
    fun `single apk packs to underscored name with identical bytes`() = runBlocking {
        val src = tmp.newFile("base_src.apk").apply { writeText("SINGLE_APK_BYTES") }
        val packer = AppPacker(tmp.newFolder("apks1"))
        val out = packer.packApp(app("com.example.app", 123, split = false, paths = listOf(src.absolutePath)))
        assertNotNull(out)
        assertEquals("com_example_app_123.apk", out!!.name)   // SPEC §5.3
        assertEquals("SINGLE_APK_BYTES", out.readText())
        // 单包产物 = 源字节副本 → 产物哈希 == 源哈希（SPEC §8.1）
        assertEquals(HashUtils.md5(src), HashUtils.md5(out))
    }

    @Test
    fun `split apk packs to apks zip with base and split entries`() = runBlocking {
        val s0 = tmp.newFile("s0.apk").apply { writeText("BASE") }
        val s1 = tmp.newFile("s1.apk").apply { writeText("SPLIT1") }
        val packer = AppPacker(tmp.newFolder("apks2"))
        val out = packer.packApp(app("com.a", 7, split = true, paths = listOf(s0.absolutePath, s1.absolutePath)))
        assertNotNull(out)
        assertEquals("com_a_7.apks", out!!.name)              // SPEC §5.3
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("base.apk"))
            assertTrue(names.contains("split_1.apk"))
            assertEquals("BASE", zip.getInputStream(zip.getEntry("base.apk")).readBytes().toString(Charsets.UTF_8))
            assertEquals("SPLIT1", zip.getInputStream(zip.getEntry("split_1.apk")).readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `non-extractable returns null`() = runBlocking {
        val packer = AppPacker(tmp.newFolder("apks3"))
        assertNull(packer.packApp(app("com.a", 1, split = false, paths = emptyList(), extractable = false)))
    }

    @Test
    fun `empty sourcePaths returns null`() = runBlocking {
        val packer = AppPacker(tmp.newFolder("apks4"))
        assertNull(packer.packApp(app("com.a", 1, split = false, paths = emptyList(), extractable = true)))
    }

    @Test
    fun `split artifact hash differs from concatenated source hash (D1 rationale)`() = runBlocking {
        val s0 = tmp.newFile("b.apk").apply { writeText("BASE_BYTES") }
        val s1 = tmp.newFile("c.apk").apply { writeText("SPLIT_BYTES") }
        val packer = AppPacker(tmp.newFolder("apks5"))
        val out = packer.packApp(app("com.a", 3, split = true, paths = listOf(s0.absolutePath, s1.absolutePath)))!!
        val artifactMd5 = HashUtils.md5(out)                    // 服务端 X-MD5 的依据（D1）
        val concatMd5 = HashUtils.md5(listOf(s0.absolutePath, s1.absolutePath))  // 列表内 AppInfo.md5 语义
        assertNotNull(artifactMd5)
        assertNotEquals("listmd5", artifactMd5)
        // SPEC §8.1：split 产物(zip) 哈希 != 原始拼接摘要 → 证明必须以 X-MD5(产物) 为准
        assertNotEquals(concatMd5, artifactMd5)
    }

    @Test
    fun `clearCache removes packed artifacts`() = runBlocking {
        val dir = tmp.newFolder("apks6")
        val packer = AppPacker(dir)
        val src = tmp.newFile("x.apk").apply { writeText("X") }
        packer.packApp(app("com.a", 1, split = false, paths = listOf(src.absolutePath)))
        assertTrue((dir.listFiles() ?: emptyArray()).isNotEmpty())
        packer.clearCache()
        assertEquals(0, (dir.listFiles() ?: emptyArray()).size)
    }
}
