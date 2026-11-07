package com.lansync.app.data.localapps

import com.lansync.app.data.model.AppInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LocalAppRepository] 测试（ARCH §3.3）：缓存读写、骨架加载、扫描刷新、包名差异、损坏缓存自愈、
 * 以及扫描/落盘失败时暴露给 UI 的 [LocalAppRepository.ScanFailure] 信号。
 * 用 Fake [InstalledAppScanner] + [TemporaryFolder]，无 Android 依赖。
 */
class LocalAppRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun app(pkg: String, vc: Long) = AppInfo(pkg, pkg, "1.0", vc, emptyList(), "m", true, 1L)

    private class FakeScanner(var result: List<AppInfo>) : InstalledAppScanner {
        var calls = 0
        var single: AppInfo? = null
        override suspend fun scanInstalledApps(): List<AppInfo> {
            calls++
            return result
        }

        override suspend fun scanSinglePackage(packageName: String): AppInfo? = single
    }

    private class FlakyScanner(var result: List<AppInfo>) : InstalledAppScanner {
        var fail = false
        override suspend fun scanInstalledApps(): List<AppInfo> {
            if (fail) error("package manager unavailable")
            return result
        }

        override suspend fun scanSinglePackage(packageName: String): AppInfo? {
            if (fail) error("package manager unavailable")
            return null
        }
    }

    @Test
    fun `scanAndRefresh reports SCAN_FAILED and keeps previous list`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FlakyScanner(listOf(app("com.a", 1)))
        val repo = LocalAppRepository(scanner, cacheFile)
        repo.scanAndRefresh()
        assertNull(repo.scanFailure.value)

        scanner.fail = true
        val result = repo.scanAndRefresh()

        assertEquals(LocalAppRepository.ScanFailure.SCAN_FAILED, repo.scanFailure.value)
        // 失败时清空本地列表会让 UI 误报「没有应用」，必须保留上一次的扫描结果
        assertEquals(1, result.apps.size)
        assertEquals(1, repo.localApps.value.size)
        assertFalse(repo.isScanning.value)

        // 下一次成功必须把失败信号清回 null，否则重试成功后仍然显示失败提示
        scanner.fail = false
        repo.scanAndRefresh()
        assertNull(repo.scanFailure.value)
    }

    @Test
    fun `scanAndRefresh reports CACHE_UNWRITABLE when cache write fails`() = runBlocking {
        val unwritable = tmp.newFolder("cache-as-directory")
        val repo = LocalAppRepository(FakeScanner(listOf(app("com.a", 1))), unwritable)

        val result = repo.scanAndRefresh()

        assertEquals(LocalAppRepository.ScanFailure.CACHE_UNWRITABLE, repo.scanFailure.value)
        // 落盘失败不影响本次内存列表：仍然可以正常展示
        assertEquals(1, result.apps.size)
    }

    @Test
    fun `hasCache false when file absent`() {
        val repo = LocalAppRepository(FakeScanner(emptyList()), File(tmp.root, "cache.json"))
        assertFalse(repo.hasCache())
    }

    @Test
    fun `scanAndRefresh updates state writes cache and reports added`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FakeScanner(listOf(app("com.a", 1), app("com.b", 2)))
        val repo = LocalAppRepository(scanner, cacheFile)

        val result = repo.scanAndRefresh()

        assertEquals(2, repo.localApps.value.size)
        assertEquals(1, scanner.calls)
        assertTrue(cacheFile.exists())
        assertTrue(repo.hasCache())
        assertEquals(setOf("com.a", "com.b"), result.addedPackages)
        assertTrue(result.removedPackages.isEmpty())
        assertFalse(repo.isScanning.value)
    }

    @Test
    fun `loadCacheSkeleton populates from cache without scanning`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        // 先用一个 repo 写缓存
        LocalAppRepository(FakeScanner(listOf(app("com.a", 1))), cacheFile).scanAndRefresh()
        // 新 repo 从缓存骨架加载，不触发扫描
        val scanner2 = FakeScanner(emptyList())
        val repo2 = LocalAppRepository(scanner2, cacheFile)
        val loaded = repo2.loadCacheSkeleton()
        assertEquals(1, loaded)
        assertEquals("com.a", repo2.localApps.value[0].packageName)
        assertEquals(0, scanner2.calls)
    }

    @Test
    fun `scanAndRefresh computes added and removed diff across runs`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FakeScanner(listOf(app("com.a", 1), app("com.b", 2)))
        val repo = LocalAppRepository(scanner, cacheFile)
        repo.scanAndRefresh()
        // 第二次：移除 com.b、新增 com.c
        scanner.result = listOf(app("com.a", 1), app("com.c", 3))
        val r2 = repo.scanAndRefresh()
        assertEquals(setOf("com.c"), r2.addedPackages)
        assertEquals(setOf("com.b"), r2.removedPackages)
    }

    @Test
    fun `corrupt cache is dropped and skeleton loads empty`() {
        val cacheFile = File(tmp.root, "cache.json").apply { writeText("{not valid json") }
        val repo = LocalAppRepository(FakeScanner(emptyList()), cacheFile)
        assertTrue(repo.hasCache())               // 文件存在且非空
        assertEquals(0, repo.loadCacheSkeleton()) // 解析失败 → 空
        assertFalse(cacheFile.exists())           // 损坏缓存被删除
        assertTrue(repo.localApps.value.isEmpty())
    }

    @Test
    fun `updateSinglePackage replaces entry and writes cache`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FakeScanner(listOf(app("com.a", 1), app("com.b", 2)))
        val repo = LocalAppRepository(scanner, cacheFile)
        repo.scanAndRefresh()

        scanner.single = app("com.a", 5)
        assertTrue(repo.updateSinglePackage("com.a"))

        assertEquals(5L, repo.localApps.value.first { it.packageName == "com.a" }.versionCode)
        assertEquals(2, repo.localApps.value.size)
        // 缓存同步落盘：下次骨架直接拿到新版本
        val repo2 = LocalAppRepository(scanner, cacheFile)
        repo2.loadCacheSkeleton()
        assertEquals(5L, repo2.localApps.value.first { it.packageName == "com.a" }.versionCode)
    }

    @Test
    fun `updateSinglePackage removes entry when package uninstalled`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FakeScanner(listOf(app("com.a", 1)))
        val repo = LocalAppRepository(scanner, cacheFile)
        repo.scanAndRefresh()

        scanner.single = null // 未安装
        assertTrue(repo.updateSinglePackage("com.a"))
        assertTrue(repo.localApps.value.isEmpty())
    }

    @Test
    fun `updateSinglePackage adds unknown package and skips no-change or blank`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        val scanner = FakeScanner(listOf(app("com.a", 1)))
        val repo = LocalAppRepository(scanner, cacheFile)
        repo.scanAndRefresh()

        // 新装包不在列表 → 追加
        scanner.single = app("com.c", 3)
        assertTrue(repo.updateSinglePackage("com.c"))
        assertEquals(2, repo.localApps.value.size)
        // 相同数据 → 无变化 false
        assertFalse(repo.updateSinglePackage("com.c"))
        // 空包名 / 空列表 → 无操作
        assertFalse(repo.updateSinglePackage(""))
        val emptyRepo = LocalAppRepository(FakeScanner(emptyList()), File(tmp.root, "cache2.json"))
        assertFalse(emptyRepo.updateSinglePackage("com.a"))
    }

    @Test
    fun `scanAndRefresh merges surgical updates newer than snapshot`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        var clock = 1_000L
        val scanner = FakeScanner(listOf(app("com.a", 1), app("com.b", 2)))
        val repo = LocalAppRepository(scanner, cacheFile) { clock }
        repo.scanAndRefresh()

        // 扫描进行期间的外科更新（时钟 2000）：a 升级、b 卸载、c 新装
        clock = 2_000L
        scanner.single = app("com.a", 5)
        assertTrue(repo.updateSinglePackage("com.a"))
        scanner.single = null
        assertTrue(repo.updateSinglePackage("com.b"))
        scanner.single = app("com.c", 3)
        assertTrue(repo.updateSinglePackage("com.c"))
        assertEquals(2, repo.localApps.value.size)

        // 并发扫描的快照取自外科之前(clock 2000 = 快照时刻)，整表替换不得回退外科结果
        clock = 2_000L
        scanner.result = listOf(app("com.a", 1), app("com.b", 2))
        repo.scanAndRefresh()
        val apps = repo.localApps.value.associateBy { it.packageName }
        assertEquals(5L, apps.getValue("com.a").versionCode)   // 快照回退被合并层拦下
        assertFalse(apps.containsKey("com.b"))                 // 外科移除不被快照复活
        assertEquals(3L, apps.getValue("com.c").versionCode)   // 快照缺失的外科新增被保留
        assertEquals(2, apps.size)
    }

    @Test
    fun `scanAndRefresh without concurrent surgical updates replaces wholesale`() = runBlocking {
        val cacheFile = File(tmp.root, "cache.json")
        var clock = 1_000L
        val scanner = FakeScanner(listOf(app("com.a", 1)))
        val repo = LocalAppRepository(scanner, cacheFile) { clock }
        repo.scanAndRefresh()

        // 快照晚于所有外科更新 → 快照内容原样生效（不受合并层影响）
        clock = 5_000L
        scanner.result = listOf(app("com.a", 9))
        repo.scanAndRefresh()
        assertEquals(9L, repo.localApps.value.single().versionCode)
    }
}
