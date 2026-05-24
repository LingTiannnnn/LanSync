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
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `md5 paths with two files produces combined hash`() {
        val f1 = tempFolder.newFile("a.txt")
        val f2 = tempFolder.newFile("b.txt")
        f1.writeText("hello")
        f2.writeText("world")
        val hash = HashUtils.md5(listOf(f1.absolutePath, f2.absolutePath))
        assertEquals(32, hash.length)
    }

    @Test
    fun `md5 nonexistent file returns empty`() {
        assertEquals("", HashUtils.md5(File("/nonexistent/path.abc")))
    }

    @Test
    fun `md5 paths with unreadable files handles gracefully`() {
        val hash = HashUtils.md5(listOf("/nonexistent/a", "/nonexistent/b"))
        assertEquals(32, hash.length)
    }
}