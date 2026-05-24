package com.lansync.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ConnectResponseBody serializes accepted=true`() {
        val body = ConnectResponseBody(accepted = true)
        val serialized = json.encodeToString(body)
        assertTrue(serialized.contains("\"accepted\":true"))
    }

    @Test
    fun `ConnectResponseBody serializes accepted=false`() {
        val body = ConnectResponseBody(accepted = false)
        val serialized = json.encodeToString(body)
        assertTrue(serialized.contains("\"accepted\":false"))
    }

    @Test
    fun `ConnectResponseBody deserializes from JSON`() {
        val raw = """{"accepted":true}"""
        val body = json.decodeFromString<ConnectResponseBody>(raw)
        assertTrue(body.accepted)
    }

    @Test
    fun `ConnectResponseBody deserializes false from JSON`() {
        val raw = """{"accepted":false}"""
        val body = json.decodeFromString<ConnectResponseBody>(raw)
        assertFalse(body.accepted)
    }

    @Test
    fun `RefreshAppListPayload serializes displayKey`() {
        val payload = RefreshAppListPayload(displayKey = "192.168.1.100:8080")
        val serialized = json.encodeToString(payload)
        assertTrue(serialized.contains("\"displayKey\":\"192.168.1.100:8080\""))
    }

    @Test
    fun `RefreshAppListPayload deserializes from JSON`() {
        val raw = """{"displayKey":"192.168.1.100:8080"}"""
        val payload = json.decodeFromString<RefreshAppListPayload>(raw)
        assertEquals("192.168.1.100:8080", payload.displayKey)
    }

    @Test
    fun `ConnectStatusResponse deserializes from JSON`() {
        val raw = """{"status":"accepted","requestId":"abc-123","accepted":true,"responderName":"Pixel","message":"OK"}"""
        val response = json.decodeFromString<ConnectStatusResponse>(raw)
        assertEquals("accepted", response.status)
        assertEquals("abc-123", response.requestId)
        assertTrue(response.accepted)
        assertEquals("Pixel", response.responderName)
    }

    @Test
    fun `AppInfo serialization roundtrip`() {
        val original = AppInfo(
            packageName = "com.example.app",
            appName = "Example",
            versionName = "2.0",
            versionCode = 200L,
            sourcePaths = listOf("/data/app/base.apk"),
            md5 = "abc123def456",
            isExtractable = true,
            fileSize = 1024000L,
            isSystemApp = false
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<AppInfo>(serialized)
        assertEquals(original.packageName, deserialized.packageName)
        assertEquals(original.appName, deserialized.appName)
        assertEquals(original.versionCode, deserialized.versionCode)
        assertEquals(original.md5, deserialized.md5)
        assertEquals(original.isExtractable, deserialized.isExtractable)
    }

    @Test
    fun `DeviceInfo serialization roundtrip`() {
        val original = DeviceInfo(
            ipAddress = "192.168.1.100",
            deviceName = "Pixel 6",
            port = 8080,
            appList = listOf(
                AppInfo(
                    packageName = "com.test.app",
                    appName = "Test",
                    versionName = "1.0",
                    versionCode = 100L,
                    sourcePaths = emptyList(),
                    md5 = "",
                    isExtractable = false,
                    fileSize = 0L
                )
            ),
            connectionState = ConnectionState.CONNECTED
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<DeviceInfo>(serialized)
        assertEquals(original.ipAddress, deserialized.ipAddress)
        assertEquals(original.deviceName, deserialized.deviceName)
        assertEquals(original.port, deserialized.port)
        assertEquals(1, deserialized.appList.size)
    }
}
