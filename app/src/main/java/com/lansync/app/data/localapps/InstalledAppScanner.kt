package com.lansync.app.data.localapps

import com.lansync.app.data.model.AppInfo

/**
 * 本机已安装应用扫描抽象。
 *
 * [LocalAppRepository] 依赖本接口而非具体 [AppScanner]，使缓存/状态逻辑可用 Fake 单测
 * （Android `PackageManager` 实现无法在 JVM 测试）。契约同旧 `data.scanner.AppScanner.scanInstalledApps`。
 */
interface InstalledAppScanner {
    /** 枚举本机已安装应用并产出 [AppInfo] 列表（含不可提取降级项）。 */
    suspend fun scanInstalledApps(): List<AppInfo>
}
