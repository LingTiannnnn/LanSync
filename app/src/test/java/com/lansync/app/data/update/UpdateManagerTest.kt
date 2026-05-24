package com.lansync.app.data.update

import com.lansync.app.data.client.AppListClient
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.UpdateInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private fun createUpdateManager(): UpdateManager {
        val mockClient = mockk<AppListClient>()
        coEvery { mockClient.fetchAppList(any(), any()) } returns emptyList()
        return UpdateManager(mockClient)
    }

    @Test
    fun `deduplicateUpdates keeps highest versionCode`() = runTest {
        val manager = createUpdateManager()
        val device = DeviceInfo(ipAddress = "192.168.1.1", deviceName = "Device1", port = 8080)

        val updates = listOf(
            UpdateInfo(
                localApp = null,
                remoteApp = createAppInfo("com.test.app", versionCode = 100L),
                providerDevice = device,
                canUpdate = true
            ),
            UpdateInfo(
                localApp = null,
                remoteApp = createAppInfo("com.test.app", versionCode = 200L),
                providerDevice = device,
                canUpdate = true
            ),
            UpdateInfo(
                localApp = null,
                remoteApp = createAppInfo("com.test.app", versionCode = 150L),
                providerDevice = device,
                canUpdate = true
            )
        )

        val deduped = manager.deduplicateUpdates(updates)

        assertEquals(1, deduped.size)
        assertEquals(200L, deduped[0].remoteApp.versionCode)
    }

    @Test
    fun `deduplicateUpdates preserves unique packages`() = runTest {
        val manager = createUpdateManager()
        val device = DeviceInfo(ipAddress = "192.168.1.1", deviceName = "Device1", port = 8080)

        val updates = listOf(
            UpdateInfo(
                localApp = null,
                remoteApp = createAppInfo("com.app.one", versionCode = 100L),
                providerDevice = device,
                canUpdate = true
            ),
            UpdateInfo(
                localApp = null,
                remoteApp = createAppInfo("com.app.two", versionCode = 200L),
                providerDevice = device,
                canUpdate = true
            )
        )

        val deduped = manager.deduplicateUpdates(updates)

        assertEquals(2, deduped.size)
    }

    @Test
    fun `deduplicateUpdates returns empty for empty input`() {
        val manager = createUpdateManager()

        val deduped = manager.deduplicateUpdates(emptyList())

        assertTrue(deduped.isEmpty())
    }

    @Test
    fun `groupByDevice groups updates correctly`() {
        val manager = createUpdateManager()
        val device1 = DeviceInfo(ipAddress = "192.168.1.1", deviceName = "Device1", port = 8080)
        val device2 = DeviceInfo(ipAddress = "192.168.1.2", deviceName = "Device2", port = 8080)

        val updates = listOf(
            UpdateInfo(null, createAppInfo("com.app.one", 100L), device1, true),
            UpdateInfo(null, createAppInfo("com.app.two", 200L), device2, true)
        )

        val grouped = manager.groupByDevice(updates)

        assertEquals(2, grouped.size)
    }

    private fun createAppInfo(packageName: String, versionCode: Long) = AppInfo(
        packageName = packageName,
        appName = "TestApp",
        versionName = versionCode.toString(),
        versionCode = versionCode,
        sourcePaths = emptyList(),
        md5 = "",
        isExtractable = true,
        fileSize = 0L
    )
}
