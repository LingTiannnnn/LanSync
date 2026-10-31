package com.lansync.app.data.sync

import com.lansync.app.data.AppNameKeyer
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

    private val um = UpdateManager(AppNameKeyer { it })

    // 拼音排序用例专用：映射常见中文名，其余按原样小写（模拟非汉字原样保留）
    private val keyed = UpdateManager(AppNameKeyer { name ->
        mapOf(
            "阿里云盘" to "aliyunpan",
            "安兔兔" to "antutu",
            "微信" to "weixin",
            "360卫士" to "360weishi"
        )[name] ?: name.lowercase()
    })

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
    fun `calculateSyncDiffs lists only apps where remote is behind local`() {
        val local = listOf(
            app("com.behind", 9, name = "B").copy(versionName = "2.0.0"),
            app("com.newer", 5, name = "N"),
            app("com.same", 3, name = "S"),
            app("com.localonly", 1, name = "L")
        )
        val devices = listOf(
            device(
                "A",
                listOf(
                    app("com.behind", 4, name = "B").copy(versionName = "1.0.0"),
                    app("com.newer", 9, name = "N"),
                    app("com.same", 3, name = "S"),
                    app("com.remoteonly", 2, name = "R")
                )
            )
        )
        val diffs = um.calculateSyncDiffs(local, devices)
        // 版本差异只收「远程落后」：远程更高归可更新，同版本与单侧存在不构成差异
        assertEquals(1, diffs.size)
        val diff = diffs.single()
        assertEquals("com.behind", diff.appInfo.packageName)
        assertEquals("2.0.0", diff.localVersion)
        assertEquals("1.0.0", diff.remoteVersion)
        assertEquals("A", diff.sourceDevice.deviceName)
    }

    @Test
    fun `calculateSyncDiffs skips system apps`() {
        val local = listOf(app("com.sys", 9, sys = true))
        val devices = listOf(device("A", listOf(app("com.sys", 4, sys = true))))
        assertTrue(um.calculateSyncDiffs(local, devices).isEmpty())
    }

    // ---------- 新增：输出按 appName 拼音 A–Z 升序（data/AppNameOrdering 口径） ----------

    @Test
    fun `calculateSyncDiffs sorts pinyin ascending with Chinese before English in same letter`() {
        val names = listOf("微信", "Alipay", "安兔兔", "阿里云盘")
        val local = names.mapIndexed { i, n -> app("com.p$i", 9, name = n) }
        val devices = listOf(device("A", names.mapIndexed { i, n -> app("com.p$i", 4, name = n) }))
        val order = keyed.calculateSyncDiffs(local, devices).map { it.appInfo.appName }
        // 同首字母段内中文整体先于英文；中文之间按全拼字母序
        assertEquals(listOf("阿里云盘", "安兔兔", "Alipay", "微信"), order)
    }

    @Test
    fun `calculateSyncDiffs puts non letter initials last`() {
        val local = listOf(app("com.d", 9, name = "360卫士"), app("com.w", 9, name = "微信"))
        val devices = listOf(
            device("A", listOf(app("com.d", 4, name = "360卫士"), app("com.w", 4, name = "微信")))
        )
        val order = keyed.calculateSyncDiffs(local, devices).map { it.appInfo.appName }
        assertEquals(listOf("微信", "360卫士"), order)
    }

    @Test
    fun `deduplicateUpdates output is sorted by appName`() {
        val updates = listOf(
            UpdateInfo(null, app("com.w", 5, name = "微信"), device("D", emptyList()), true),
            UpdateInfo(null, app("com.a", 5, name = "阿里云盘"), device("D", emptyList()), true)
        )
        val order = keyed.deduplicateUpdates(updates).map { it.remoteApp.appName }
        assertEquals(listOf("阿里云盘", "微信"), order)
    }
}
