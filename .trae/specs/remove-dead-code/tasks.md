# Tasks

- [x] Task 1: 清理 Result 工具类——删除 `Result.kt` 源文件和 `ResultTest.kt` 测试文件
- [x] Task 2: 清理 AppConfig 中未使用的配置字段——删除 `requestTimeoutMs`、`downloadBufferSize`、`sendBufferSize` 三个字段
- [x] Task 3: 清理 AppRepository 死代码——删除 `enrichedDevices` 属性、`fetchDeviceAppList()` 方法、`installDownloadedFile(File)` 重载、4 个已废弃常量
- [x] Task 4: 清理 UpdateManager 死代码——删除 `groupByDevice()` 方法及 UpdateManagerTest 中对应测试用例
- [x] Task 5: 清理 MainViewModel 死代码——删除 `performInitialScan()`、`scanLocalApps()`、`checkForUpdates()` 方法和 `lastDownloadedUpdateInfo` 属性
- [x] Task 6: 清理 AppIcon.kt 死代码——删除 `AppIconCache` 对象和 `clearAppIconCache()` 函数
- [x] Task 7: 清理 KtorServer 死路由——删除 `/api/app/{packageName}/{versionCode}` 路由及未使用的 import（ZipEntry、ZipOutputStream）
- [x] Task 8: 清理 AppListClient.ConnectResult.Error 未使用的密封类变体
- [x] Task 9: 修正测试文件——重命名 `AppPackerTest` 为 `HashUtilsConsistencyTest`
- [x] Task 10: 运行全部单元测试验证清理后代码无回归

# Task Dependencies
- Task 1-9 相互独立，可并行执行
- Task 10 依赖 Task 1-9 全部完成
