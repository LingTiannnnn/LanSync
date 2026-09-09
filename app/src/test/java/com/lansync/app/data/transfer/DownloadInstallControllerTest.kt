package com.lansync.app.data.transfer

import com.lansync.app.data.installer.ApkInstaller
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.UpdateInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DownloadInstallController] 测试（ARCH §3.4）：下载/安装的进度与状态机（downloadProgress / installStatus）。
 * 用 MockK mock [LanSyncClient]（下载已在 LanSyncClientTest 覆盖 D1）与 [ApkInstaller]（Android 耦合），
 * 聚焦本控制器的**状态转换与委托**逻辑。
 */
class DownloadInstallControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun app(pkg: String, vc: Long) = AppInfo(pkg, pkg, "1.0", vc, emptyList(), "m", true, 1L)
    private fun update(pkg: String, vc: Long) =
        UpdateInfo(null, app(pkg, vc), DeviceInfo("1.1.1.1", "A", 1), true)

    @Test
    fun `downloadApp success sets COMPLETED 100`() = runBlocking {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        val f = tmp.newFile("com_a_1.apks")
        coEvery { client.downloadApksFile(any(), any(), any(), any(), any()) } returns
            LanSyncClient.DownloadResult.Success(f)

        val ctrl = DownloadInstallController(client, installer)
        val r = ctrl.downloadApp(update("com.a", 1L))

        assertTrue(r is LanSyncClient.DownloadResult.Success)
        assertEquals(DownloadInstallController.DownloadProgress.Status.COMPLETED, ctrl.downloadProgress.value?.status)
        assertEquals(100, ctrl.downloadProgress.value?.progress)
    }

    @Test
    fun `downloadApp error sets FAILED 0`() = runBlocking {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        coEvery { client.downloadApksFile(any(), any(), any(), any(), any()) } returns
            LanSyncClient.DownloadResult.Error("MD5 verification failed")

        val ctrl = DownloadInstallController(client, installer)
        ctrl.downloadApp(update("com.a", 1L))

        assertEquals(DownloadInstallController.DownloadProgress.Status.FAILED, ctrl.downloadProgress.value?.status)
        assertEquals(0, ctrl.downloadProgress.value?.progress)
    }

    @Test
    fun `installApp with downloaded file returns Success`() {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        val f = tmp.newFile("com_a_1.apks")
        every { client.getDownloadedFile("com.a", 1L) } returns f
        every { installer.installApks(f) } returns ApkInstaller.InstallationResult.Success

        val ctrl = DownloadInstallController(client, installer)
        val r = ctrl.installApp(update("com.a", 1L))

        assertTrue(r is ApkInstaller.InstallationResult.Success)
        assertTrue(ctrl.installStatus.value is DownloadInstallController.InstallStatus.Success)
    }

    @Test
    fun `installApp without file returns Failed not found`() {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        every { client.getDownloadedFile(any(), any()) } returns null

        val ctrl = DownloadInstallController(client, installer)
        val r = ctrl.installApp(update("com.a", 1L))

        assertTrue(r is ApkInstaller.InstallationResult.Error)
        assertTrue(ctrl.installStatus.value is DownloadInstallController.InstallStatus.Failed)
    }

    @Test
    fun `downloadAndInstallApp chains download then install`() = runBlocking {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        val f = tmp.newFile("com_a_1.apks")
        coEvery { client.downloadApksFile(any(), any(), any(), any(), any()) } returns
            LanSyncClient.DownloadResult.Success(f)
        every { installer.installApks(f) } returns ApkInstaller.InstallationResult.Success

        val ctrl = DownloadInstallController(client, installer)
        val r = ctrl.downloadAndInstallApp(update("com.a", 1L))

        assertTrue(r is ApkInstaller.InstallationResult.Success)
    }

    @Test
    fun `installDownloadedFile missing returns Failed`() {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        every { client.getDownloadedFileFromName("nope.apks") } returns null

        val ctrl = DownloadInstallController(client, installer)
        val r = ctrl.installDownloadedFile("nope.apks", "com.a")

        assertTrue(r is ApkInstaller.InstallationResult.Error)
        assertTrue(ctrl.installStatus.value is DownloadInstallController.InstallStatus.Failed)
    }

    @Test
    fun `clearDownloadProgress and clearInstallStatus reset to null`() = runBlocking {
        val client = mockk<LanSyncClient>()
        val installer = mockk<ApkInstaller>()
        coEvery { client.downloadApksFile(any(), any(), any(), any(), any()) } returns
            LanSyncClient.DownloadResult.Error("x")
        val ctrl = DownloadInstallController(client, installer)
        ctrl.downloadApp(update("com.a", 1L))
        assertTrue(ctrl.downloadProgress.value != null)
        ctrl.clearDownloadProgress()
        ctrl.clearInstallStatus()
        assertEquals(null, ctrl.downloadProgress.value)
        assertEquals(null, ctrl.installStatus.value)
    }
}
