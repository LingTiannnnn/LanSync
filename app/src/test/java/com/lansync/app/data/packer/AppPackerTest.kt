package com.lansync.app.data.packer

import com.lansync.app.data.HashUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppPackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `getMd5OfPackedFile returns valid 32-char hex`() {
        val file = tempFolder.newFile("packed.apks")
        file.writeBytes(ByteArray(1024) { (it % 256).toByte() })

        val md5 = HashUtils.md5(file)

        assertEquals(32, md5.length)
        assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hash consistent for same content`() {
        val f1 = tempFolder.newFile("a.bin")
        val f2 = tempFolder.newFile("b.bin")
        f1.writeText("hello world")
        f2.writeText("hello world")
        assertEquals(HashUtils.md5(f1), HashUtils.md5(f2))
        assertEquals(HashUtils.sha256(f1), HashUtils.sha256(f2))
    }

    @Test
    fun `sha256 empty file produces correct length`() {
        val file = tempFolder.newFile("empty.bin")
        val hash = HashUtils.sha256(file)
        assertEquals(64, hash.length)
    }

    @Test
    fun `md5 nonexistent pack returns empty`() {
        val file = File(tempFolder.root, "nonexistent.apks")
        assertEquals("", HashUtils.md5(file))
    }
}