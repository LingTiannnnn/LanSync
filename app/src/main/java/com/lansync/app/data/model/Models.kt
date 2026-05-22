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
    val isSystemApp: Boolean = false
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
    val appList: List<AppInfo> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCOVERED,
    val lastSeenTimeMs: Long = System.currentTimeMillis(),
    val connectionError: String? = null
) {
    val displayKey: String get() = "$ipAddress:$port"
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
    val timestamp: Long,
    var status: RequestStatus = RequestStatus.PENDING
) {
    enum class RequestStatus { PENDING, ACCEPTED, REJECTED, TIMEOUT }
}

@Serializable
data class DisconnectPayload(
    val displayKey: String
)
