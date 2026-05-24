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
    fun `hash consistent for same content`() {
        val f1 = tempFolder.newFile("a.bin")
        val f2 = tempFolder.newFile("b.bin")
        f1.writeText("hello world")
        f2.writeText("hello world")
        assertEquals(HashUtils.md5(f1), HashUtils.md5(f2))
    }

    @Test
    fun `md5 nonexistent pack returns empty`() {
        val file = File(tempFolder.root, "nonexistent.apks")
        assertEquals("", HashUtils.md5(file))
    }
}