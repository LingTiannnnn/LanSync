package com.lansync.app.data.sync

import com.lansync.app.data.AppConfig
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateCoordinator] 测试（ARCH §3.2）：combine 触发、节流窗口、立即重算、空输入。
 * 用可控时钟 [nowMillis] 验证节流，避免真实时间等待；上游用 [MutableStateFlow] 驱动。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {

    private fun app(pkg: String, vc: Long, sys: Boolean = false) =
        AppInfo(pkg, pkg, "1.0", vc, emptyList(), "m", true, 1L, isSystemApp = sys)

    private fun device(name: String, apps: List<AppInfo>) =
        DeviceInfo(ipAddress = "1.1.1.1", deviceName = name, port = 1, appList = apps)

    @Test
    fun `emits availableUpdates and syncDiffs when data ready`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        val coord = UpdateCoordinator(UpdateManager(), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 10_000L }
        coord.start()
        runCurrent()
        assertEquals(1, coord.availableUpdates.value.size)
        assertEquals("com.a", coord.availableUpdates.value[0].remoteApp.packageName)
        assertTrue(coord.syncDiffs.value.isNotEmpty())
        coord.stop()
    }

    @Test
    fun `throttle suppresses recalc within window then allows after`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        var clock = 10_000L
        val coord = UpdateCoordinator(UpdateManager(), localApps, connected, AppConfig.DEFAULT, backgroundScope) { clock }
        coord.start()
        runCurrent()
        assertEquals(5L, coord.availableUpdates.value[0].remoteApp.versionCode)

        // 窗口内（+1s < 5s 节流）：新值不触发重算，仍是旧结果
        clock = 11_000L
        connected.value = listOf(device("A", listOf(app("com.a", 7))))
        runCurrent()
        assertEquals(5L, coord.availableUpdates.value[0].remoteApp.versionCode)

        // 越过窗口（+10s）：重算生效
        clock = 20_000L
        connected.value = listOf(device("A", listOf(app("com.a", 9))))
        runCurrent()
        assertEquals(9L, coord.availableUpdates.value[0].remoteApp.versionCode)
        coord.stop()
    }

    @Test
    fun `recalculateNow bypasses throttle and start`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 9)))))
        val coord = UpdateCoordinator(UpdateManager(), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 0L }
        coord.recalculateNow() // 未 start，直接重算
        assertEquals(1, coord.availableUpdates.value.size)
        assertEquals(9L, coord.availableUpdates.value[0].remoteApp.versionCode)
    }

    @Test
    fun `empty localApps yields no updates`() = runTest {
        val localApps = MutableStateFlow(emptyList<AppInfo>())
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        val coord = UpdateCoordinator(UpdateManager(), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 10_000L }
        coord.recalculateNow()
        assertTrue(coord.availableUpdates.value.isEmpty())
    }
}
