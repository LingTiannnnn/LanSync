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

@Serializable
data class DeviceInfo(
    val ipAddress: String,
    val deviceName: String,
    val port: Int,
    val instanceId: String = "",
    val appList: List<AppInfo> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCOVERED,
    val lastSeenTimeMs: Long = System.currentTimeMillis(),
    val connectionError: String? = null
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

data class SyncDiff(
    val appInfo: AppInfo,
    val localVersion: String?,
    val remoteVersion: String,
    val sourceDevice: DeviceInfo,
    val diffType: DiffType
) {
    enum class DiffType { NEWER_ON_REMOTE, ONLY_ON_REMOTE, SAME_VERSION, ONLY_ON_LOCAL }
}

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
    var status: RequestStatus = RequestStatus.PENDING
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
