package com.lansync.app.data

import org.junit.Assert.*
import org.junit.Test

class ResultTest {

    @Test
    fun `success wraps value`() {
        val result = Result.success(42)
        assertTrue(result.isSuccess)
        assertFalse(result.isError)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `error returns message`() {
        val result = Result.error("test error")
        assertTrue(result.isError)
        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrDefault returns data on success`() {
        val result = Result.success("hello")
        assertEquals("hello", result.getOrDefault("default"))
    }

    @Test
    fun `getOrDefault returns default on error`() {
        val result: Result<String> = Result.error("fail")
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `map transforms success`() {
        val result = Result.success(10).map { it * 2 }
        assertEquals(20, result.getOrNull())
    }

    @Test
    fun `map preserves error`() {
        val error: Result<Int> = Result.error("fail")
        val mapped = error.map { 42 }
        assertTrue(mapped.isError)
    }

    @Test
    fun `runCatching captures success`() {
        val result = Result.runCatching { "hello".length }
        assertTrue(result.isSuccess)
        assertEquals(5, result.getOrNull())
    }

    @Test
    fun `runCatching captures exception`() {
        val result: Result<String> = Result.runCatching { throw RuntimeException("oops") }
        assertTrue(result.isError)
    }
}