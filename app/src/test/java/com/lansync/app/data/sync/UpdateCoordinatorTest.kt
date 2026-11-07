package com.lansync.app.data.sync

import com.lansync.app.data.AppConfig
import com.lansync.app.data.AppNameKeyer
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
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
        // com.a 远端更高 → 可更新；com.b 本地更高 → 版本差异（远程落后）
        val localApps = MutableStateFlow(listOf(app("com.a", 1), app("com.b", 9)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5), app("com.b", 4)))))
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 10_000L }
        coord.start()
        runCurrent()
        assertEquals(1, coord.availableUpdates.value.size)
        assertEquals("com.a", coord.availableUpdates.value[0].remoteApp.packageName)
        assertEquals(1, coord.syncDiffs.value.size)
        assertEquals("com.b", coord.syncDiffs.value[0].appInfo.packageName)
        coord.stop()
    }

    @Test
    fun `throttle defers in-window emission to window end with latest input`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        var clock = 10_000L
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { clock }
        coord.start()
        runCurrent()
        assertEquals(5L, coord.availableUpdates.value[0].remoteApp.versionCode)

        // 窗口内（+1s < 5s 节流）：leading 不触发,尾沿补算
        clock = 11_000L
        connected.value = listOf(device("A", listOf(app("com.a", 7))))
        runCurrent()
        assertEquals(5L, coord.availableUpdates.value[0].remoteApp.versionCode)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(7L, coord.availableUpdates.value[0].remoteApp.versionCode)

        // 越过窗口（clock 已 +9s）：立即重算
        clock = 20_000L
        connected.value = listOf(device("A", listOf(app("com.a", 9))))
        runCurrent()
        assertEquals(9L, coord.availableUpdates.value[0].remoteApp.versionCode)
        coord.stop()
    }

    @Test
    fun `burst within window collapses to single trailing recalc with latest input`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        var clock = 10_000L
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { clock }
        coord.start()
        runCurrent()

        // 窗口内连续 3 次变化 → 只在尾沿补算一次,取最新输入
        clock = 11_000L
        connected.value = listOf(device("A", listOf(app("com.a", 6))))
        clock = 12_000L
        connected.value = listOf(device("A", listOf(app("com.a", 7))))
        clock = 13_000L
        connected.value = listOf(device("A", listOf(app("com.a", 8))))
        runCurrent()
        assertEquals(5L, coord.availableUpdates.value[0].remoteApp.versionCode)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(8L, coord.availableUpdates.value[0].remoteApp.versionCode)
        coord.stop()
    }

    @Test
    fun `clearing connected devices empties available updates`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        var clock = 10_000L
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { clock }
        coord.start()
        runCurrent()
        assertEquals(1, coord.availableUpdates.value.size)

        // 设备全部断开 → 输出清空（不留旧值）
        clock = 20_000L
        connected.value = emptyList()
        runCurrent()
        assertTrue(coord.availableUpdates.value.isEmpty())
        assertTrue(coord.syncDiffs.value.isEmpty())
        coord.stop()
    }

    @Test
    fun `recalculateNow bypasses throttle and start`() = runTest {
        val localApps = MutableStateFlow(listOf(app("com.a", 1)))
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 9)))))
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 0L }
        coord.recalculateNow() // 未 start，直接重算
        assertEquals(1, coord.availableUpdates.value.size)
        assertEquals(9L, coord.availableUpdates.value[0].remoteApp.versionCode)
    }

    @Test
    fun `empty localApps yields no updates`() = runTest {
        val localApps = MutableStateFlow(emptyList<AppInfo>())
        val connected = MutableStateFlow(listOf(device("A", listOf(app("com.a", 5)))))
        val coord = UpdateCoordinator(UpdateManager(AppNameKeyer { it }), localApps, connected, AppConfig.DEFAULT, backgroundScope) { 10_000L }
        coord.recalculateNow()
        assertTrue(coord.availableUpdates.value.isEmpty())
    }
}
