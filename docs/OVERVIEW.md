# 项目速览（OVERVIEW）

LanSync = **局域网 P2P Android 应用更新同步工具**（对外说明见 `README.md`）。同一 Wi-Fi 内两台设备直连，全程明文 HTTP，不依赖公网、不需要 Root / Shizuku / ADB。

核心能力：JmDNS 自动发现同网段设备 → 扫描本机已安装应用并与远端列表做版本对比 → 推荐最佳来源（同包名取最高 `versionCode`，平级取 `deviceName` 字母序更小者）→ 单 APK 直传或 split APK 打包（`.apks`，实为 zip）→ 下载校验后 FileProvider + `ACTION_VIEW` 委托系统/第三方安装器 → 产物可经 SAF 保存到外部存储。

> 架构与代码地图见 `docs/ARCHITECTURE.md`；字节级协议契约见 `docs/SPEC.md`；构建运行前提见 `docs/BUILD.md`。

## 1. 模块结构

单 Gradle 模块 `:app`（`settings.gradle.kts`）。release 构建 minify + shrinkResources。依赖仓库走阿里云镜像。

## 2. 权限与 Manifest（12 项 uses-permission）

| 权限 | 用途 |
|---|---|
| `INTERNET` | 服务端/客户端通信 |
| `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` | 网络状态与 SSID |
| `CHANGE_WIFI_MULTICAST_STATE` | **多播锁，收 mDNS 必需** |
| `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | 取 SSID 的历史兼容路径（能否移除属权限审查，见 `docs/ROADMAP.md`） |
| `NEARBY_WIFI_DEVICES`（`neverForLocation`） | 声明但无运行时请求 |
| `QUERY_ALL_PACKAGES` | 扫描全部应用必需，Play 上架敏感 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS` | 前台服务与常驻通知 |

- `usesCleartextTraffic=true` + `res/xml/network_security_config.xml`（明文 HTTP 必需）。
- `<service .ForegroundSyncService android:foregroundServiceType="specialUse" android:exported="false">` + `<property>`。
- FileProvider authority `${applicationId}.fileprovider`，映射 `apks/`、`downloads/`（`file_paths.xml`）。
- 唯一 Activity：`MainActivity`（LAUNCHER）；启动器底色 Teal `#00696B`（`res/values/colors.xml`）。
- `specialUse` 的 Play 上架说明待补（`docs/ROADMAP.md`）。

## 3. 本地持久化与缓存（无 Room / 无 DataStore）

| 位置 | 内容 | 生命周期 |
|---|---|---|
| `SharedPreferences("lansync_device")` → `device_instance_id` | 持久化 UUID，设备身份稳定标识 | 永久 |
| `SharedPreferences`（配对历史） | 成功配对过的 `instanceId` 集合（自动接受判据） | 永久 |
| `filesDir/local_apps_cache.json` | 最近一次扫描的 `AppInfo` 列表（**私有格式，非线上协议**） | 每次扫描覆盖 |
| `filesDir/app_icons/*.png` | 图标磁盘缓存 | 随卸载清理 |
| `cacheDir/apks/*` | 打包产物 | 启动即 `clearCache()` |
| `cacheDir/downloads/*` | 已下载 `.apk`/`.apks` | 用户手动删除 |
| `getExternalFilesDir/lansync_debug.log(.bakN)` | `FileLogger` 文件日志 | 5MB 轮转 + 3 备份 |

**重启语义**：无跨进程数据库，"重置即卸载"。缓存 JSON 的格式约束与自愈要求属规范，见 `docs/CONVENTIONS.md` §2。
