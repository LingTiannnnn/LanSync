package com.lansync.app.data.sync

import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新 `data.sync.UpdateManager`（纯逻辑）测试。
 *
 * 迁移自旧 `data.update.UpdateManagerTest`（3 例 deduplicateUpdates），并**补齐旧实现零覆盖的 findUpdates**
 * 与 `calculateSyncDiffs`（TEST-PLAN §2.3 缺口）。新 UpdateManager 对「已填充 appList 的设备」做纯比较，
 * 无 suspend/无网络，故不需要 runTest。
 */
class UpdateManagerTest {

    private val um = UpdateManager()

    private fun app(pkg: String, vc: Long, sys: Boolean = false, name: String = pkg) =
        AppInfo(pkg, name, "1.0", vc, emptyList(), "m", true, 1L, isSystemApp = sys)

    private fun device(name: String, apps: List<AppInfo>) =
        DeviceInfo(ipAddress = "1.1.1.1", deviceName = name, port = 1, appList = apps)

    // ---------- 迁移：deduplicateUpdates（旧 UpdateManagerTest 3 例） ----------

    @Test
    fun `deduplicateUpdates keeps highest versionCode`() {
        val updates = listOf(
            UpdateInfo(null, app("com.a", 1), device("A", emptyList()), true),
            UpdateInfo(null, app("com.a", 5), device("B", emptyList()), true)
        )
        val result = um.deduplicateUpdates(updates)
        assertEquals(1, result.size)
        assertEquals(5L, result[0].remoteApp.versionCode)
    }

    @Test
    fun `deduplicateUpdates preserves unique packages`() {
        val updates = listOf(
            UpdateInfo(null, app("com.a", 1), device("A", emptyList()), true),
            UpdateInfo(null, app("com.b", 2), device("B", emptyList()), true)
        )
        assertEquals(2, um.deduplicateUpdates(updates).size)
    }

    @Test
    fun `deduplicateUpdates returns empty for empty input`() {
        assertTrue(um.deduplicateUpdates(emptyList()).isEmpty())
    }

    // ---------- 新增：dedup 平级取 deviceName 字母序更小者（SPEC/ARCH §3.2） ----------

    @Test
    fun `deduplicateUpdates tie breaks by smaller deviceName`() {
        val updates = listOf(
            UpdateInfo(null, app("com.a", 5), device("Zeta", emptyList()), true),
            UpdateInfo(null, app("com.a", 5), device("Alpha", emptyList()), true)
        )
        val r = um.deduplicateUpdates(updates)
        assertEquals(1, r.size)
        assertEquals("Alpha", r[0].providerDevice.deviceName)
    }

    // ---------- 新增：findUpdates（旧实现零覆盖） ----------

    @Test
    fun `findUpdates includes only remote strictly newer than local`() {
        val local = listOf(app("com.a", 1), app("com.b", 10))
        val devices = listOf(device("A", listOf(app("com.a", 2), app("com.b", 5))))
        val updates = um.findUpdates(local, devices)
        assertEquals(1, updates.size)
        assertEquals("com.a", updates[0].remoteApp.packageName)
        assertTrue(updates[0].canUpdate)
    }

    @Test
    fun `findUpdates skips system apps on either side`() {
        val local = listOf(app("com.sys", 1, sys = true), app("com.a", 1))
        val devices = listOf(device("A", listOf(app("com.sys", 9, sys = true), app("com.a", 2, sys = true))))
        // com.sys: 本地系统应用 → 跳过；com.a: 远端系统应用 → 跳过
        assertTrue(um.findUpdates(local, devices).isEmpty())
    }

    @Test
    fun `findUpdates skips packages absent locally`() {
        val local = listOf(app("com.a", 1))
        val devices = listOf(device("A", listOf(app("com.only.remote", 5))))
        assertTrue(um.findUpdates(local, devices).isEmpty())
    }

    // ---------- 新增：calculateSyncDiffs 四类型 ----------

    @Test
    fun `calculateSyncDiffs classifies four diff types`() {
        val local = listOf(
            app("com.newer", 5, name = "N"),
            app("com.same", 3, name = "S"),
            app("com.localonly", 1, name = "L")
        )
        val devices = listOf(
            device("A", listOf(app("com.newer", 9, name = "N"), app("com.same", 3, name = "S"), app("com.remoteonly", 2, name = "R")))
        )
        val byPkg = um.calculateSyncDiffs(local, devices).associateBy { it.appInfo.packageName }
        assertEquals(SyncDiff.DiffType.NEWER_ON_REMOTE, byPkg["com.newer"]?.diffType)
        assertEquals(SyncDiff.DiffType.SAME_VERSION, byPkg["com.same"]?.diffType)
        assertEquals(SyncDiff.DiffType.ONLY_ON_REMOTE, byPkg["com.remoteonly"]?.diffType)
        assertEquals(SyncDiff.DiffType.ONLY_ON_LOCAL, byPkg["com.localonly"]?.diffType)
    }

    @Test
    fun `calculateSyncDiffs skips system apps`() {
        val local = listOf(app("com.sys", 1, sys = true))
        val devices = listOf(device("A", listOf(app("com.sys", 9, sys = true))))
        assertTrue(um.calculateSyncDiffs(local, devices).isEmpty())
    }
}
