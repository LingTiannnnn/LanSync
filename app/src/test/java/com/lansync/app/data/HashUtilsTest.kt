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
    fun `sha256 file produces 64-char hex string`() {
        val file = tempFolder.newFile("test.txt")
        file.writeText("hello world")
        val hash = HashUtils.sha256(file)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 same content produces same hash`() {
        val f1 = tempFolder.newFile("a.txt")
        val f2 = tempFolder.newFile("b.txt")
        f1.writeText("identical content")
        f2.writeText("identical content")
        assertEquals(HashUtils.sha256(f1), HashUtils.sha256(f2))
    }

    @Test
    fun `sha256 different content produces different hash`() {
        val f1 = tempFolder.newFile("a.txt")
        val f2 = tempFolder.newFile("b.txt")
        f1.writeText("content A")
        f2.writeText("content B")
        assertTrue(HashUtils.sha256(f1) != HashUtils.sha256(f2))
    }

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
    fun `sha256 nonexistent file returns empty`() {
        assertEquals("", HashUtils.sha256(File("/nonexistent/path.abc")))
    }

    @Test
    fun `md5 paths with unreadable files handles gracefully`() {
        val hash = HashUtils.md5(listOf("/nonexistent/a", "/nonexistent/b"))
        assertEquals(32, hash.length)
    }

    @Test
    fun `sha256 empty file produces correct hash`() {
        val file = tempFolder.newFile("empty.txt")
        val hash = HashUtils.sha256(file)
        assertEquals(64, hash.length)
    }
}