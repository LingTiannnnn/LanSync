package com.lansync.app.data.server

import com.lansync.app.data.FileLogger
import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponseBody
import com.lansync.app.data.model.ConnectStatusResponse
import com.lansync.app.data.model.DeviceInfoResponse
import com.lansync.app.data.model.DisconnectPayload
import com.lansync.app.data.model.GenericStatusResponse
import com.lansync.app.data.model.LanSyncErrorCode
import com.lansync.app.data.model.LanSyncErrorDto
import com.lansync.app.data.model.RefreshAppListPayload
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "LanSyncRouting"

/**
 * 与 SPEC.md §1.2 完全一致的 JSON 配置（prettyPrint / isLenient / ignoreUnknownKeys，
 * encodeDefaults 取默认 false）。这是「等于默认值的可选字段被省略」字节级契约的根基（SPEC.md §1.2.1）。
 */
val LanSyncJson: Json = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * 可独立 install 的路由模块（ARCHITECTURE.md §3.5 的测试接缝）。
 *
 * 忠实复现 SPEC.md §3（10 条路由的方法/路径/成功响应体形状/错误状态码）、§4（Content-Type）、
 * §5.1（下载三响应头）。与旧实现的差异（均为 SPEC/ARCHITECTURE 允许的内部改进）：
 * - 错误响应体由 text/plain 改为结构化 [LanSyncErrorDto]（application/json），状态码不变，
 *   不回显 e.message（SPEC.md §3.2 / ARCHITECTURE.md §7）。
 * - 消除 503「Server not ready」分支：DI 后 pairingStore 恒非空，该惰性初始化竞态不复存在
 *   （SPEC.md §3.1 #4 的 503 仅为旧设计产物；新客户端对旧服务端的 503 仍按失败容忍）。
 * - 两个 download 路由的错误码优先级严格保持各自现状（SPEC.md §3.1 #7 vs #8）。
 */
fun Application.lanSyncModule(delegate: ServerApiDelegate, pairingStore: PairingStore) {
    install(ContentNegotiation) { json(LanSyncJson) }

    routing {
        // #1 GET /api/ping → {"status":"pong"}
        get("/api/ping") {
            call.respond(GenericStatusResponse(status = "pong"))
        }

        // #2 GET /api/applist → [AppInfo,...]（provider 空则 []）
        get("/api/applist") {
            val apps = delegate.provideAppList()
            FileLogger.d(TAG, "GET /api/applist -> ${apps.size} apps")
            call.respond(apps)
        }

        // #3 GET /api/deviceinfo → {"deviceName":X}（version 为死字段，encodeDefaults=false 下省略，SPEC §2.9）
        get("/api/deviceinfo") {
            call.respond(DeviceInfoResponse(deviceName = delegate.deviceName()))
        }

        // #4 POST /api/connect/request → 200 pending / 409 duplicate / 400 bad json
        post("/api/connect/request") {
            val payload = try {
                call.receive<ConnectRequestPayload>()
            } catch (e: Exception) {
                FileLogger.e(TAG, "connect/request parse failed", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    LanSyncErrorDto.of(LanSyncErrorCode.INVALID_REQUEST, "Invalid request")
                )
                return@post
            }
            FileLogger.i(TAG, "POST /api/connect/request id=${payload.requestId} from=${payload.requesterName}")
            if (pairingStore.receiveRequest(payload)) {
                call.respond(ConnectStatusResponse(status = "pending", requestId = payload.requestId))
            } else {
                call.respond(
                    HttpStatusCode.Conflict,
                    LanSyncErrorDto.of(LanSyncErrorCode.CONFLICT, "Duplicate request")
                )
            }
        }

        // #5 GET /api/connect/status/{requestId} → 恒 200；缺失/异常一律 pending（失败安全，SPEC §2.6）
        get("/api/connect/status/{requestId}") {
            val requestId = call.parameters["requestId"] ?: ""
            if (requestId.isEmpty()) {
                call.respond(ConnectStatusResponse(status = "pending"))
                return@get
            }
            val response = try {
                pairingStore.getStatus(requestId)
            } catch (e: Exception) {
                FileLogger.e(TAG, "connect/status error", e)
                null
            }
            if (response == null) {
                call.respond(ConnectStatusResponse(status = "pending"))
            } else {
                val status = if (response.accepted) "accepted" else "rejected"
                call.respond(
                    ConnectStatusResponse(
                        status = status,
                        requestId = requestId,
                        accepted = response.accepted,
                        responderName = response.responderName,
                        message = response.message
                    )
                )
            }
        }

        // #6 POST /api/connect/response/{requestId} → 200 / 400 / 404 / 500
        post("/api/connect/response/{requestId}") {
            val requestId = call.parameters["requestId"] ?: ""
            if (requestId.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LanSyncErrorDto.of(LanSyncErrorCode.INVALID_REQUEST, "Invalid request")
                )
                return@post
            }
            val body = try {
                call.receive<ConnectResponseBody>()
            } catch (e: Exception) {
                FileLogger.e(TAG, "connect/response parse failed", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    LanSyncErrorDto.of(LanSyncErrorCode.INVALID_REQUEST, "Invalid request")
                )
                return@post
            }
            val response = try {
                pairingStore.respondToRequest(requestId, body.accepted)
            } catch (e: Exception) {
                FileLogger.e(TAG, "connect/response error", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    LanSyncErrorDto.of(LanSyncErrorCode.INTERNAL, "Internal error")
                )
                return@post
            }
            if (response != null) {
                call.respond(response)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    LanSyncErrorDto.of(LanSyncErrorCode.REQUEST_NOT_FOUND, "Request not found or already handled")
                )
            }
        }

        // #7 GET /api/download/{packageName} → 最高 versionCode；先判全不可提取(403)再取最高版本(404)
        get("/api/download/{packageName}") {
            val packageName = call.parameters["packageName"] ?: ""
            val apps = delegate.provideAppList()
            val extractableApps = apps.filter { it.packageName == packageName && it.isExtractable }
            val nonExtractable = apps.any { it.packageName == packageName && !it.isExtractable }

            if (nonExtractable && extractableApps.isEmpty()) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LanSyncErrorDto.of(
                        LanSyncErrorCode.NOT_EXTRACTABLE,
                        "App is a system/protected app and cannot be extracted for transfer"
                    )
                )
                return@get
            }
            val app = extractableApps.maxByOrNull { it.versionCode }
            if (app == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    LanSyncErrorDto.of(LanSyncErrorCode.APP_NOT_FOUND, "App not found")
                )
                return@get
            }
            sendPackedFile(call, delegate, app)
        }

        // #8 GET /api/download/{packageName}/{versionCode} → 精确版本；先判无匹配(404)再判不可提取(403)
        get("/api/download/{packageName}/{versionCode}") {
            val packageName = call.parameters["packageName"] ?: ""
            val versionCode = call.parameters["versionCode"]?.toLongOrNull() ?: 0L
            val apps = delegate.provideAppList()
            val app = apps.find { it.packageName == packageName && it.versionCode == versionCode }

            if (app == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    LanSyncErrorDto.of(LanSyncErrorCode.APP_NOT_FOUND, "App not found")
                )
                return@get
            }
            if (!app.isExtractable) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LanSyncErrorDto.of(
                        LanSyncErrorCode.NOT_EXTRACTABLE,
                        "App is a system/protected app and cannot be extracted for transfer"
                    )
                )
                return@get
            }
            sendPackedFile(call, delegate, app)
        }

        // #9 POST /api/disconnect → 200 {} / 400
        post("/api/disconnect") {
            val payload = try {
                call.receive<DisconnectPayload>()
            } catch (e: Exception) {
                FileLogger.e(TAG, "disconnect parse failed", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    LanSyncErrorDto.of(LanSyncErrorCode.INVALID_REQUEST, "Invalid request")
                )
                return@post
            }
            val key = if (payload.identityKey.isNotEmpty()) payload.identityKey else payload.displayKey
            FileLogger.i(TAG, "POST /api/disconnect from=$key")
            delegate.onDisconnect(key)
            call.respond(GenericStatusResponse())
        }

        // #10 POST /api/refresh-applist → 200 {} / 400
        post("/api/refresh-applist") {
            val payload = try {
                call.receive<RefreshAppListPayload>()
            } catch (e: Exception) {
                FileLogger.e(TAG, "refresh-applist parse failed", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    LanSyncErrorDto.of(LanSyncErrorCode.INVALID_REQUEST, "Invalid request")
                )
                return@post
            }
            if (payload.displayKey.isNotEmpty()) {
                delegate.onRefreshAppList(payload.displayKey)
            }
            call.respond(GenericStatusResponse())
        }
    }
}

/**
 * 打包并流式发送下载产物，复现 SPEC.md §5.1 三响应头 + §4 Content-Type。
 *
 * X-MD5 = 打包产物文件的实时 MD5（决策 D1「以传输产物为准」的唯一权威，SPEC.md §8.3）。
 * 打包失败/产物缺失/异常一律 500 PACK_FAILED（不回显 e.message）。
 */
private suspend fun sendPackedFile(
    call: ApplicationCall,
    delegate: ServerApiDelegate,
    app: AppInfo
) {
    val packedFile: File? = try {
        delegate.pack(app)
    } catch (e: Exception) {
        FileLogger.e(TAG, "pack failed for ${app.packageName}", e)
        null
    }
    if (packedFile == null || !packedFile.exists()) {
        call.respond(
            HttpStatusCode.InternalServerError,
            LanSyncErrorDto.of(LanSyncErrorCode.PACK_FAILED, "Failed to pack app")
        )
        return
    }

    val actualMd5 = HashUtils.md5(packedFile) ?: ""
    call.response.header("X-MD5", actualMd5)
    call.response.header("X-File-Size", packedFile.length().toString())
    call.response.header("Content-Disposition", "attachment; filename=\"${packedFile.name}\"")

    val isSingleApk = packedFile.name.endsWith(".apk") && !packedFile.name.endsWith(".apks")
    val contentType = if (isSingleApk) ContentType.Application.OctetStream else ContentType.Application.Zip
    FileLogger.i(TAG, "download ${app.packageName} v${app.versionCode} -> ${packedFile.length()} bytes, ct=$contentType")
    call.respondOutputStream(contentType) {
        packedFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                write(buffer, 0, bytesRead)
            }
        }
        flush()
    }
}
