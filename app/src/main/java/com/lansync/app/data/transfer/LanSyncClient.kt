package com.lansync.app.data.transfer

import com.lansync.app.data.FileLogger
import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponseBody
import com.lansync.app.data.model.ConnectStatusResponse
import com.lansync.app.data.model.DisconnectPayload
import com.lansync.app.data.model.RefreshAppListPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "LanSyncClient"

private fun defaultRegularClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES))
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private fun defaultDownloadClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .connectionPool(ConnectionPool(maxIdleConnections = 2, keepAliveDuration = 1, TimeUnit.MINUTES))
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private val defaultJson: Json = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * HTTP 客户端（SPEC.md §5）——旧 [com.lansync.app.data.client.AppListClient] 的「实现重写」。
 *
 * 关键差异：
 * - **新 MD5 语义（决策 D1，SPEC.md §8.3）**：下载校验以响应头 `X-MD5`（传输产物哈希）为**唯一权威**；
 *   移除 `expectedMd5` 兜底参数与分支；**缺失 X-MD5 直接判失败并删除文件**，不再静默用列表 md5 兜底。
 * - **无 Android Context 依赖**：下载目录 [downloadsDir] 与两个 OkHttpClient 均构造注入，可在 JVM 单测。
 * - 文件命名/包名解析统一走 [DownloadedFileName]（消除 P7 重复实现）。
 * - 错误文案不回显 `e.message`（ARCHITECTURE.md §7.2），异常细节只进 FileLogger。
 *
 * 超时/间隔常量取自 SPEC.md §7.2；JSON 配置与 SPEC.md §1.2 一致。
 */
class LanSyncClient(
    private val downloadsDir: File,
    private val client: OkHttpClient = defaultRegularClient(),
    private val downloadClient: OkHttpClient = defaultDownloadClient(),
    private val json: Json = defaultJson
) {
    init {
        downloadsDir.mkdirs()
    }

    // ---------- 配对 ----------

    /**
     * 发送配对请求（SPEC.md §2.3）。`requesterIp` 由调用方（连接层）提供，保持本类无网络接口探测依赖。
     * 成功返回生成的 requestId，非 2xx 或异常返回 null。
     */
    suspend fun sendConnectRequest(
        targetIp: String,
        targetPort: Int,
        requesterName: String,
        requesterIp: String,
        requesterPort: Int,
        requesterInstanceId: String = ""
    ): String? = withContext(Dispatchers.IO) {
        try {
            val requestId = UUID.randomUUID().toString()
            val payload = ConnectRequestPayload(
                requestId = requestId,
                requesterName = requesterName,
                requesterIp = requesterIp,
                requesterPort = requesterPort,
                requesterInstanceId = requesterInstanceId,
                timestamp = System.currentTimeMillis()
            )
            val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://$targetIp:$targetPort/api/connect/request")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) requestId else null
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendConnectRequest failed to $targetIp:$targetPort", e)
            null
        }
    }

    /**
     * 轮询配对状态（SPEC.md §7.2：默认 30s 总超时 / 500ms 间隔）。
     * 超时返回 [ConnectResult.Timeout]（文案与旧实现一致）。
     */
    suspend fun pollConnectStatus(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
        pollIntervalMs: Long = POLL_INTERVAL_MS
    ): ConnectResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = tryPollOnce(targetIp, targetPort, requestId, pollIntervalMs)
            if (result != null) return@withContext result
        }
        ConnectResult.Timeout("连接超时（${timeoutMs / 1000}秒内未收到响应）")
    }

    private suspend fun tryPollOnce(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        pollIntervalMs: Long
    ): ConnectResult? {
        val url = "http://$targetIp:$targetPort/api/connect/status/$requestId"
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful || body.isEmpty()) {
                    delay(pollIntervalMs)
                    return null
                }
                val parsed = parseConnectStatus(body)
                if (parsed == null) delay(pollIntervalMs)
                parsed
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "poll exception for $requestId: ${e.message}")
            delay(pollIntervalMs)
            null
        }
    }

    /** 解析 status 响应体（SPEC.md §2.6）：仅识别 pending/accepted/rejected，其余→null（继续轮询）。 */
    internal fun parseConnectStatus(body: String): ConnectResult? {
        return try {
            val response = json.decodeFromString<ConnectStatusResponse>(body)
            when (response.status) {
                "pending" -> null
                "accepted" -> ConnectResult.Accepted(response.responderName ?: "Unknown")
                "rejected" -> ConnectResult.Rejected(response.message ?: "Rejected")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun sendConnectResponse(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        accepted: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(ConnectResponseBody(accepted))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://$targetIp:$targetPort/api/connect/response/$requestId")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendConnectResponse failed", e)
            false
        }
    }

    // ---------- 查询 ----------

    suspend fun fetchAppList(ipAddress: String, port: Int): List<AppInfo>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("http://$ipAddress:$port/api/applist").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext null
                json.decodeFromString<List<AppInfo>>(body)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "fetchAppList failed from $ipAddress:$port", e)
            null
        }
    }

    suspend fun fetchDeviceInfo(ipAddress: String, port: Int): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("http://$ipAddress:$port/api/deviceinfo").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext null
                json.decodeFromString<Map<String, String>>(body)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "fetchDeviceInfo failed", e)
            null
        }
    }

    suspend fun pingDevice(ipAddress: String, port: Int, timeoutMs: Long = PING_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("http://$ipAddress:$port/api/ping").get().build()
                val pingClient = client.newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                pingClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }

    // ---------- 通知 ----------

    suspend fun sendDisconnectNotification(
        targetIp: String,
        targetPort: Int,
        localDisplayKey: String,
        localIdentityKey: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = DisconnectPayload(displayKey = localDisplayKey, identityKey = localIdentityKey)
            val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("http://$targetIp:$targetPort/api/disconnect").post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendDisconnectNotification failed", e)
            false
        }
    }

    suspend fun sendRefreshAppListNotification(
        targetIp: String,
        targetPort: Int,
        localDisplayKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(RefreshAppListPayload(localDisplayKey))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://$targetIp:$targetPort/api/refresh-applist").post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendRefreshAppListNotification failed", e)
            false
        }
    }

    // ---------- 下载（新 MD5 语义 D1） ----------

    suspend fun downloadApksFile(
        ipAddress: String,
        port: Int,
        packageName: String,
        versionCode: Long,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = performDownload(
        tag = "downloadApksFile",
        url = "http://$ipAddress:$port/api/download/$packageName/$versionCode",
        defaultFileName = DownloadedFileName.defaultDownloadName(packageName, versionCode),
        onProgress = onProgress
    )

    suspend fun downloadLatestApksFile(
        ipAddress: String,
        port: Int,
        packageName: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = performDownload(
        tag = "downloadLatestApksFile",
        url = "http://$ipAddress:$port/api/download/$packageName",
        defaultFileName = DownloadedFileName.defaultDownloadName(packageName, null),
        onProgress = onProgress
    )

    private suspend fun performDownload(
        tag: String,
        url: String,
        defaultFileName: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(200) ?: ""
                    FileLogger.e(TAG, "$tag HTTP ${response.code}: $errorBody")
                    return@withContext DownloadResult.Error("HTTP error: ${response.code}")
                }

                val serverMd5 = response.header("X-MD5") ?: ""
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    FileLogger.e(TAG, "$tag: empty response body")
                    return@withContext DownloadResult.Error("Empty response body")
                }

                val contentDisposition = response.header("Content-Disposition")
                val fileName = contentDisposition?.substringAfter("filename=\"")?.substringBeforeLast("\"")
                    ?.takeIf { it.isNotEmpty() } ?: defaultFileName
                val destination = File(downloadsDir, fileName)

                val contentLength = response.body?.contentLength()?.takeIf { it > 0 }
                    ?: response.header("X-File-Size")?.toLongOrNull()
                    ?: -1L
                destination.outputStream().use { out ->
                    copyWithProgress(inputStream, out, contentLength, onProgress)
                }

                if (!destination.exists() || destination.length() == 0L) {
                    FileLogger.e(TAG, "$tag: file empty or missing after write")
                    return@withContext DownloadResult.Error("Downloaded file is empty")
                }

                // 决策 D1（SPEC.md §8.3）：X-MD5 是唯一权威；缺头即失败，绝不猜测/兜底。
                if (serverMd5.isEmpty()) {
                    destination.delete()
                    FileLogger.e(TAG, "$tag: missing X-MD5 header -> reject (D1)")
                    return@withContext DownloadResult.Error("Missing X-MD5 header")
                }
                val actualMd5 = HashUtils.md5(destination) ?: ""
                if (actualMd5 != serverMd5) {
                    destination.delete()
                    FileLogger.w(TAG, "$tag: MD5 MISMATCH actual=$actualMd5 server=$serverMd5")
                    return@withContext DownloadResult.Error("MD5 verification failed")
                }

                FileLogger.i(TAG, "$tag SUCCESS -> ${destination.length()} bytes")
                DownloadResult.Success(destination)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "$tag EXCEPTION", e)
            DownloadResult.Error("Download failed")
        }
    }

    private fun copyWithProgress(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        val buffer = ByteArray(65536)
        var bytesRead: Int
        var totalBytesRead = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            if (contentLength > 0 && onProgress != null) {
                onProgress((totalBytesRead * 100 / contentLength).toInt().coerceIn(0, 100))
            }
        }
    }

    // ---------- 本地下载文件管理 ----------

    fun getDownloadedFile(packageName: String, versionCode: Long): File? =
        DownloadedFileName.resolveDownloadedFile(downloadsDir, packageName, versionCode)

    fun getDownloadedFileFromName(fileName: String): File? {
        val file = File(downloadsDir, fileName)
        return if (file.exists() && file.isFile) file else null
    }

    fun getDownloadedFiles(): List<DownloadedFileInfo> =
        downloadsDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".apks") || it.name.endsWith(".apk")) }
            ?.map { file ->
                DownloadedFileInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    lastModified = file.lastModified(),
                    packageName = DownloadedFileName.parsePackageName(file.name),
                    isSplitApk = file.name.endsWith(".apks")
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun deleteDownloadedFiles(fileNames: List<String>): Int {
        var deleted = 0
        for (name in fileNames) {
            val file = File(downloadsDir, name)
            if (file.exists() && file.delete()) deleted++
        }
        return deleted
    }

    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
    }

    data class DownloadedFileInfo(
        val fileName: String,
        val filePath: String,
        val fileSize: Long,
        val lastModified: Long,
        val packageName: String = "",
        val isSplitApk: Boolean = false
    )

    sealed class ConnectResult {
        data class Accepted(val responderName: String) : ConnectResult()
        data class Rejected(val message: String) : ConnectResult()
        data class Timeout(val message: String) : ConnectResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    companion object {
        /** SPEC.md §7.2 */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val PING_TIMEOUT_MS = 3_000L
    }
}
