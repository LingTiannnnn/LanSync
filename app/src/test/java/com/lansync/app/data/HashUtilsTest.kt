package com.lansync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HashUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `md5 file produces 32-char hex string`() {
        val file = tempFolder.newFile("test.txt")
        file.writeText("hello world")
        val hash = HashUtils.md5(file)
        assertTrue("hash should not be null", hash != null)
        assertEquals(32, hash!!.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `md5 paths with two files produces combined hash`() {
        val f1 = tempFolder.newFile("a.txt")
        val f2 = tempFolder.newFile("b.txt")
        f1.writeText("hello")
        f2.writeText("world")
        val hash = HashUtils.md5(listOf(f1.absolutePath, f2.absolutePath))
        assertTrue("hash should not be null", hash != null)
        assertEquals(32, hash!!.length)
    }

    @Test
    fun `md5 nonexistent file returns null`() {
        val hash = HashUtils.md5(File("/nonexistent/path.abc"))
        assertEquals(null, hash)
    }

    @Test
    fun `md5 paths with unreadable files returns null`() {
        // SPEC §8.2/§8.3：不可读文件不再静默跳过 → null 传播，消灭幽灵指纹 d41d8cd98f00b204e9800998ecf8427e
        val hash = HashUtils.md5(listOf("/nonexistent/a", "/nonexistent/b"))
        assertNull(hash)
    }

    @Test
    fun `md5 paths with partially unreadable file returns null`() {
        val readable = tempFolder.newFile("ok.txt").apply { writeText("data") }
        // 任一文件不可读即 null 传播（不再产出部分摘要）
        val hash = HashUtils.md5(listOf(readable.absolutePath, "/nonexistent/x"))
        assertNull(hash)
    }

    @Test
    fun `md5 of empty path list returns null`() {
        assertNull(HashUtils.md5(emptyList()))
    }
}