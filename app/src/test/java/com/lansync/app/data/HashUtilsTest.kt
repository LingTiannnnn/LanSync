package com.lansync.app.data

import org.junit.Assert.assertEquals
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
    fun `md5 paths with unreadable files handles gracefully`() {
        val hash = HashUtils.md5(listOf("/nonexistent/a", "/nonexistent/b"))
        // 当所有文件不可读时，返回空字符串作为 hash
        assertEquals(32, hash!!.length)
    }
}