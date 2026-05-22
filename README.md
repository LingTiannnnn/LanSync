# LanSync

**P2P Android App Update Sync over LAN**

一个基于局域网的P2P应用更新同步工具，支持自动发现设备、版本比较、Split APK (.apks)支持，并委托第三方安装器进行安装。无需Root或Shizuku权限。

## ✨ 特性

- **自动设备发现** - 使用JmDNS自动发现局域网内的设备
- **版本智能比较** - 自动检测可用更新并推荐最佳来源
- **Split APK 支持** - 完整支持 Android App Bundle (AAB) 拆分包
- **第三方安装器** - 无缝集成系统或第三方APK安装器
- **无需特殊权限** - 无需Root、Shizuku或ADB权限
- **Material You 设计** - 采用最新的 Material3 设计规范

## 🛠️ 技术栈

- **语言**: Kotlin
- **UI框架**: Jetpack Compose (Material3)
- **网络通信**: Ktor Server + OkHttp Client
- **设备发现**: JmDNS
- **序列化**: Kotlinx Serialization

## 📱 功能概览

### 设备发现
- 自动扫描局域网内运行 LanSync 的设备
- 实时显示设备状态（在线/离线）
- 支持设备名称识别

### 应用同步
- 扫描本地已安装应用
- 获取远程设备的应用列表
- 智能版本对比，找出可更新应用

### APK打包与传输
- 支持单APK和Split APK打包
- 生成 .apks 格式的压缩包
- 通过HTTP传输，支持断点续传（待实现）

### 安装集成
- 自动调用系统或第三方安装器
- 支持多种APK格式（.apk, .apks）
- 安全的文件URI共享
