package com.lansync.app.data.client

import android.content.Context
import com.lansync.app.data.FileLogger
import com.lansync.app.data.HashUtils
import com.lansync.app.data.NetworkUtils
import com.lansync.app.data.connection.ConnectionManager
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
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class AppListClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(maxIdleConnections = 2, keepAliveDuration = 1, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val downloadsDir: File by lazy {
        File(context.cacheDir, "downloads").also { it.mkdirs() }
    }

    suspend fun sendConnectRequest(
        targetIp: String,
        targetPort: Int,
        localDeviceName: String,
        localPort: Int,
        localInstanceId: String = ""
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val requestId = UUID.randomUUID().toString()
                val payload = ConnectRequestPayload(
                    requestId = requestId,
                    requesterName = localDeviceName,
                    requesterIp = NetworkUtils.getLocalIpAddress(),
                    requesterPort = localPort,
                    requesterInstanceId = localInstanceId,
                    timestamp = System.currentTimeMillis()
                )

                val url = "http://$targetIp:$targetPort/api/connect/request"
                val jsonBody = json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        requestId
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to send connect request to $targetIp:$targetPort", e)
                null
            }
        }
    }

    suspend fun pollConnectStatus(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        timeoutMs: Long = ConnectionManager.CONNECT_TIMEOUT_MS,
        pollIntervalMs: Long = ConnectionManager.POLL_INTERVAL_MS
    ): ConnectResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            FileLogger.i(TAG, "=== POLL START === target=$targetIp:$targetPort requestId=$requestId timeout=${timeoutMs}ms interval=${pollIntervalMs}ms")
            var pollCount = 0

            run {
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    pollCount++

                    val result = tryPollOnce(targetIp, targetPort, requestId, pollIntervalMs)
                    if (result != null) {
                        val totalElapsed = System.currentTimeMillis() - startTime
                        FileLogger.i(TAG, "=== POLL RESULT === $result after ${totalElapsed}ms ($pollCount polls)")
                        return@run result
                    }
                }
                val totalElapsed = System.currentTimeMillis() - startTime
                val msg = "连接超时（${timeoutMs / 1000}秒内未收到响应）"
                FileLogger.e(TAG, "=== POLL TIMEOUT === after ${totalElapsed}ms ($pollCount polls) target=$targetIp:$targetPort")
                ConnectResult.Timeout(msg)
            }
        }
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

                if (!response.isSuccessful) {
                    FileLogger.w(TAG, "Poll HTTP error: ${response.code} for $url")
                    delay(pollIntervalMs)
                    return null
                }

                if (body.isEmpty()) {
                    delay(pollIntervalMs)
                    return null
                }

                val pollResult = tryParseConnectStatus(body)
                if (pollResult != null) {
                    return pollResult
                }

                delay(pollIntervalMs)
                null
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Poll exception for $requestId: ${e.javaClass.simpleName}: ${e.message}")
            delay(pollIntervalMs)
            null
        }
    }

    private fun tryParseConnectStatus(body: String): ConnectResult? {
        try {
            val statusResponse = json.decodeFromString<ConnectStatusResponse>(body)
            when (statusResponse.status) {
                "pending" -> return null
                "accepted" -> {
                    val responderName = statusResponse.responderName ?: "Unknown"
                    FileLogger.i(TAG, "Poll ACCEPTED: responder=$responderName")
                    return ConnectResult.Accepted(responderName)
                }
                "rejected" -> {
                    val message = statusResponse.message ?: "Rejected"
                    FileLogger.i(TAG, "Poll REJECTED: message=$message")
                    return ConnectResult.Rejected(message)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    suspend fun sendConnectResponse(
        targetIp: String,
        targetPort: Int,
        requestId: String,
        accepted: Boolean
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = json.encodeToString(ConnectResponseBody(accepted))
                    .toRequestBody("application/json".toMediaType())

                val url = "http://$targetIp:$targetPort/api/connect/response/$requestId"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to send connect response to $targetIp:$targetPort", e)
                false
            }
        }
    }

    sealed class ConnectResult {
        data class Accepted(val responderName: String) : ConnectResult()
        data class Rejected(val message: String) : ConnectResult()
        data class Timeout(val message: String) : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    suspend fun fetchAppList(ipAddress: String, port: Int): List<AppInfo>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$ipAddress:$port/api/applist"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        return@withContext null
                    }

                    json.decodeFromString<List<AppInfo>>(responseBody)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to fetch app list from $ipAddress:$port", e)
                null
            }
        }
    }

    suspend fun fetchDeviceInfo(ipAddress: String, port: Int): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$ipAddress:$port/api/deviceinfo"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        return@withContext null
                    }

                    json.decodeFromString<Map<String, String>>(responseBody)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun pingDevice(ipAddress: String, port: Int, timeoutMs: Long = 3000L): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$ipAddress:$port/api/ping"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val pingClient = client.newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()

                pingClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun sendDisconnectNotification(
        targetIp: String,
        targetPort: Int,
        localDisplayKey: String,
        localIdentityKey: String = ""
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$targetIp:$targetPort/api/disconnect"
                val payload = DisconnectPayload(displayKey = localDisplayKey, identityKey = localIdentityKey)
                val jsonBody = json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun sendRefreshAppListNotification(
        targetIp: String,
        targetPort: Int,
        localDisplayKey: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$targetIp:$targetPort/api/refresh-applist"
                val jsonBody = json.encodeToString(RefreshAppListPayload(localDisplayKey))
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun downloadApksFile(
        ipAddress: String,
        port: Int,
        packageName: String,
        versionCode: Long,
        expectedMd5: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "downloadApksFile START: $packageName v$versionCode from $ipAddress:$port (expectedMd5=${expectedMd5.take(8)}...)")

            try {
                val url = "http://$ipAddress:$port/api/download/${packageName}/${versionCode}"
                FileLogger.d(TAG, "downloadApksFile: GET $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        FileLogger.e(TAG, "downloadApksFile HTTP ${response.code}: $errorBody")
                        return@withContext DownloadResult.Error("HTTP error: ${response.code}: $errorBody")
                    }

                    val serverMd5 = response.header("X-MD5") ?: ""
                    FileLogger.d(TAG, "downloadApksFile: X-MD5 from server = ${serverMd5.take(8)}... (expectedMd5 param = ${expectedMd5.take(8)}...)")

                    val inputStream = response.body?.byteStream()
                    if (inputStream == null) {
                        FileLogger.e(TAG, "downloadApksFile: empty response body")
                        return@withContext DownloadResult.Error("Empty response body")
                    }

                    val fileName = "${packageName.replace(".", "_")}_${versionCode}.apks"
                    val destination = File(downloadsDir, fileName)

                    FileLogger.d(TAG, "downloadApksFile: writing to ${destination.absolutePath}")
                    val contentLength = response.body?.contentLength()?.takeIf { it > 0 }
                        ?: response.header("X-File-Size")?.toLongOrNull()
                        ?: -1L
                    FileLogger.d(TAG, "downloadApksFile: contentLength=$contentLength (from response=${response.body?.contentLength()}, X-File-Size=${response.header("X-File-Size")})")
                    destination.outputStream().use { outputStream ->
                        copyWithProgress(inputStream, outputStream, contentLength, onProgress)
                    }

                    if (!destination.exists() || destination.length() == 0L) {
                        FileLogger.e(TAG, "downloadApksFile: file empty or missing after write")
                        return@withContext DownloadResult.Error("Downloaded file is empty")
                    }

                    val actualMd5 = HashUtils.md5(destination)
                    val verifyMd5 = serverMd5.ifEmpty { expectedMd5 }
                    FileLogger.d(TAG, "downloadApksFile: size=${destination.length()} actualMd5=${actualMd5.take(8)}... verifyAgainst=${verifyMd5.take(8)}... (source=${if (serverMd5.isNotEmpty()) "server-header" else "expected-param"})")
                    if (actualMd5 != verifyMd5 && verifyMd5.isNotEmpty()) {
                        FileLogger.w(TAG, "downloadApksFile: MD5 MISMATCH! actual=$actualMd5 expected=$verifyMd5")
                        destination.delete()
                        return@withContext DownloadResult.Error("MD5 verification failed")
                    }

                    FileLogger.i(TAG, "downloadApksFile SUCCESS: $packageName -> ${destination.length()} bytes")
                    DownloadResult.Success(destination)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "downloadApksFile EXCEPTION: ${e.message}", e)
                return@withContext DownloadResult.Error("Download failed: ${e.message}")
            }
        }
    }

    suspend fun downloadLatestApksFile(
        ipAddress: String,
        port: Int,
        packageName: String,
        expectedMd5: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "downloadLatestApksFile START: $packageName from $ipAddress:$port")

            try {
                val url = "http://$ipAddress:$port/api/download/$packageName"
                FileLogger.d(TAG, "downloadLatestApksFile: GET $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        FileLogger.e(TAG, "downloadLatestApksFile HTTP ${response.code}: $errorBody")
                        return@withContext DownloadResult.Error("HTTP error: ${response.code}: $errorBody")
                    }

                    val serverMd5 = response.header("X-MD5") ?: ""
                    FileLogger.d(TAG, "downloadLatestApksFile: X-MD5 from server = ${serverMd5.take(8)}... (expectedMd5 param = ${expectedMd5.take(8)}...)")

                    val inputStream = response.body?.byteStream()
                    if (inputStream == null) {
                        FileLogger.e(TAG, "downloadLatestApksFile: empty response body")
                        return@withContext DownloadResult.Error("Empty response body")
                    }

                    val contentDisposition = response.header("Content-Disposition")
                    val fileName = contentDisposition?.substringAfter("filename=\"")?.substringBeforeLast("\"")
                        ?: "${packageName.replace(".", "_")}.apks"
                    val destination = File(downloadsDir, fileName)

                    FileLogger.d(TAG, "downloadLatestApksFile: writing to ${destination.absolutePath}")
                    val contentLength = response.body?.contentLength()?.takeIf { it > 0 }
                        ?: response.header("X-File-Size")?.toLongOrNull()
                        ?: -1L
                    destination.outputStream().use { outputStream ->
                        copyWithProgress(inputStream, outputStream, contentLength, onProgress)
                    }

                    if (!destination.exists() || destination.length() == 0L) {
                        FileLogger.e(TAG, "downloadLatestApksFile: file empty or missing after write")
                        return@withContext DownloadResult.Error("Downloaded file is empty")
                    }

                    val actualMd5 = HashUtils.md5(destination)
                    val verifyMd5 = serverMd5.ifEmpty { expectedMd5 }
                    if (actualMd5 != verifyMd5 && verifyMd5.isNotEmpty()) {
                        FileLogger.w(TAG, "downloadLatestApksFile: MD5 MISMATCH! actual=$actualMd5 expected=$verifyMd5")
                        destination.delete()
                        return@withContext DownloadResult.Error("MD5 verification failed")
                    }

                    FileLogger.i(TAG, "downloadLatestApksFile SUCCESS: $packageName -> ${destination.length()} bytes")
                    DownloadResult.Success(destination)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "downloadLatestApksFile EXCEPTION: ${e.message}", e)
                return@withContext DownloadResult.Error("Download failed: ${e.message}")
            }
        }
    }

    private fun copyWithProgress(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytesRead = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            if (contentLength > 0 && onProgress != null) {
                val percent = ((totalBytesRead * 100 / contentLength).toInt().coerceIn(0, 100))
                onProgress(percent)
            }
        }
    }

    fun getDownloadedFile(packageName: String, versionCode: Long): File? {
        val fileName = "${packageName.replace(".", "_")}_${versionCode}.apks"
        val file = File(downloadsDir, fileName)
        val found = file.exists()
        if (found) {
            FileLogger.d(TAG, "getDownloadedFile: FOUND $fileName (${file.length()} bytes)")
        } else {
            FileLogger.w(TAG, "getDownloadedFile: NOT FOUND $fileName (downloadsDir=${downloadsDir.absolutePath}, exists=${downloadsDir.exists()})")
        }
        return if (found) file else null
    }

    fun cleanupOldDownloads(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        downloadsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }

    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
    }

    fun getDownloadsSize(): Long {
        return downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    data class DownloadedFileInfo(
        val fileName: String,
        val filePath: String,
        val fileSize: Long,
        val lastModified: Long,
        val packageName: String = ""
    )

    fun getDownloadedFiles(): List<DownloadedFileInfo> {
        return downloadsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apks") }
            ?.map { file ->
                val packageName = extractPackageName(file.name)
                DownloadedFileInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    lastModified = file.lastModified(),
                    packageName = packageName
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    private fun extractPackageName(fileName: String): String {
        val nameWithoutExt = fileName.removeSuffix(".apks")
        val parts = nameWithoutExt.split("_")
        val versionEnd = parts.indexOfLast { it.toLongOrNull() != null }
        return if (versionEnd <= 0) {
            nameWithoutExt.replace("_", ".")
        } else {
            parts.take(versionEnd).joinToString(".")
        }
    }

    fun deleteDownloadedFiles(fileNames: List<String>): Int {
        var deleted = 0
        for (name in fileNames) {
            val file = File(downloadsDir, name)
            if (file.exists() && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    fun getDownloadedFileFromName(fileName: String): File? {
        val file = File(downloadsDir, fileName)
        return if (file.exists() && file.isFile) file else null
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    companion object {
        private const val TAG = "AppListClient"
    }
}
