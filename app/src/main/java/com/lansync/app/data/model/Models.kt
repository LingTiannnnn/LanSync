package com.lansync.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val sourcePaths: List<String>,
    val md5: String,
    val isExtractable: Boolean,
    val fileSize: Long,
    val isSystemApp: Boolean = false,
    val isSplitApk: Boolean = false
)

enum class ConnectionState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    ERROR,
    DISCONNECTED,
    RECONNECTING,
    CONNECTION_TIMEOUT
}

/**
 * 连接失败的**机器可读**原因（ARCH §7 错误协议的客户端侧对应物）。
 *
 * 领域层不再生产中文句子：`DefaultConnectionCoordinator` 只填 [Kind] 与计数，
 * 用户可见文案由 UI 按 `kind` 映射到 `strings.xml`（`ui/string/FailureText.kt`）。
 * 异常细节、对端原文只进 `FileLogger`，不进 UI。
 *
 * 本类型只挂在本地领域模型 [DeviceInfo] 上，不是跨设备 DTO（SPEC §2.2 冻结的是
 * `DeviceInfoResponse` 等响应体），因此不参与线上字节契约。
 */
@Serializable
data class ConnectionFailure(
    val kind: Kind,
    val retry: Int = 0,
    val retryTotal: Int = 0,
    val waitSeconds: Int = 0,
) {
    enum class Kind {
        /** 连接请求发不出去 / 目标无响应 */
        SEND_NO_RESPONSE,

        /** 对方在配对里选择拒绝 */
        REJECTED_BY_PEER,

        /** 轮询配对结果到总超时仍未得到答复 */
        WAIT_TIMEOUT,

        /** 心跳连续失败但在容忍档内 */
        LINK_UNSTABLE,

        /** 心跳连续失败进入重连档 */
        RECONNECTING,

        /** 心跳失败达到上限，判定设备离线 */
        DEVICE_OFFLINE,

        /** 其它异常（细节只进日志） */
        UNEXPECTED
    }
}

/**
 * 下载/安装链路的失败类别（本地领域类型，**不上线**）。
 *
 * 与 [ConnectionFailure] 同一条规矩：data 层只给出机器可读的类别，诊断原文（OkHttp/PM 的
 * 英文 exception message）只进 `FileLogger`；UI 按本枚举映射 `strings.xml`，
 * 不得渲染原文（CONVENTIONS §3「文案唯一来源」）。
 */
enum class InstallFailure {
    /** 下载产物丢失或还没下载完 */
    FILE_MISSING,

    /** 下载阶段失败（HTTP / MD5 / 空响应等） */
    DOWNLOAD_FAILED,

    /** 本机没有能处理该包的安装程序 */
    INSTALLER_UNAVAILABLE,

    /** 拉起安装程序时抛异常 */
    INSTALLER_EXCEPTION
}

@Serializable
data class DeviceInfo(
    val ipAddress: String,
    val deviceName: String,
    val port: Int,
    val instanceId: String = "",
    val appList: List<AppInfo> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCOVERED,
    val lastSeenTimeMs: Long = System.currentTimeMillis(),
    val connectionFailure: ConnectionFailure? = null
) {
    val displayKey: String get() = "$ipAddress:$port"

    val identityKey: String get() =
        if (instanceId.isNotEmpty()) instanceId
        else "$deviceName@$ipAddress"
}

data class UpdateInfo(
    val localApp: AppInfo?,
    val remoteApp: AppInfo,
    val providerDevice: DeviceInfo,
    val canUpdate: Boolean
)

data class RemoteAppEntry(
    val app: AppInfo,
    val sourceDevice: DeviceInfo
)

/** 版本差异（同步 Tab）：双端都安装且**本地版本更高**（远程落后）。远程更高归「可更新」，不在此列。 */
data class SyncDiff(
    val appInfo: AppInfo,
    val localVersion: String,
    val remoteVersion: String,
    val sourceDevice: DeviceInfo,
)

@Serializable
data class ConnectRequestPayload(
    val requestId: String,
    val requesterName: String,
    val requesterIp: String,
    val requesterPort: Int,
    val requesterInstanceId: String = "",
    val timestamp: Long
)

@Serializable
data class ConnectResponsePayload(
    val requestId: String,
    val accepted: Boolean,
    val responderName: String? = null,
    val message: String? = null
)

data class IncomingConnectRequest(
    val requestId: String,
    val requesterName: String,
    val requesterIp: String,
    val requesterPort: Int,
    val requesterInstanceId: String = "",
    val timestamp: Long,
    val status: RequestStatus = RequestStatus.PENDING
) {
    enum class RequestStatus { PENDING, ACCEPTED, REJECTED, TIMEOUT }

    val identityKey: String get() =
        if (requesterInstanceId.isNotEmpty()) requesterInstanceId
        else "$requesterName@$requesterIp"
}

@Serializable
data class DisconnectPayload(
    val displayKey: String,
    val identityKey: String = ""
)

@Serializable
data class ConnectStatusResponse(
    val status: String,
    val requestId: String? = null,
    val accepted: Boolean = false,
    val responderName: String? = null,
    val message: String? = null
)

@Serializable
data class DeviceInfoResponse(
    val deviceName: String,
    val version: String = "1.0"
)

@Serializable
data class GenericStatusResponse(
    val status: String = "ok"
)

@Serializable
data class ConnectResponseBody(
    val accepted: Boolean
)

@Serializable
data class RefreshAppListPayload(
    val displayKey: String
)
