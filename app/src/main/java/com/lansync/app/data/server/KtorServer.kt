package com.lansync.app.data.server

import android.content.Context
import com.lansync.app.data.connection.ConnectionManager
import com.lansync.app.data.FileLogger
import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponseBody
import com.lansync.app.data.model.ConnectStatusResponse
import com.lansync.app.data.model.DeviceInfoResponse
import com.lansync.app.data.model.DisconnectPayload
import com.lansync.app.data.model.GenericStatusResponse
import com.lansync.app.data.model.RefreshAppListPayload
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KtorServer(private val context: Context) {

    private var server: NettyApplicationEngine? = null
    private var actualPort: Int = 0
    private var appListProvider: (() -> List<AppInfo>)? = null
    private var packer: (suspend (AppInfo) -> File?)? = null

    var connectionManager: ConnectionManager? = null
        private set

    private var disconnectHandler: ((String) -> Unit)? = null
    private var refreshAppListHandler: ((String) -> Unit)? = null

    fun setAppListProvider(provider: () -> List<AppInfo>) {
        this.appListProvider = provider
    }

    fun setPacker(packer: suspend (AppInfo) -> File?) {
        this.packer = packer
    }

    fun setDisconnectHandler(handler: (String) -> Unit) {
        this.disconnectHandler = handler
    }

    fun setRefreshAppListHandler(handler: (String) -> Unit) {
        this.refreshAppListHandler = handler
    }

    fun initConnectionManager(localDeviceName: String) {
        connectionManager = ConnectionManager(localDeviceName)
    }

    suspend fun start(port: Int = 0): Int {
        if (server != null) return actualPort

        val newServer = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            routing {
                get("/api/ping") {
                    call.respond(GenericStatusResponse(status = "pong"))
                }

                get("/api/applist") {
                    val apps = appListProvider?.invoke() ?: emptyList()
                    FileLogger.d("KtorServer", "GET /api/applist -> returning ${apps.size} apps")
                    call.respond(apps)
                }

                post("/api/connect/request") {
                    withContext(Dispatchers.IO) {
                        try {
                            val payload = call.receive<ConnectRequestPayload>()
                            FileLogger.i("KtorServer", "POST /api/connect/request from=${payload.requesterName} (${payload.requesterIp}:${payload.requesterPort}) id=${payload.requestId}")

                            val manager = connectionManager

                            if (manager == null) {
                                FileLogger.e("KtorServer", "POST /api/connect/request: connectionManager is NULL!")
                                call.respondText("Server not ready", status = HttpStatusCode.ServiceUnavailable)
                                return@withContext
                            }

                            val received = manager.receiveRequest(payload)

                            if (received) {
                                FileLogger.d("KtorServer", "POST /api/connect/request -> 200 OK, id=${payload.requestId}")
                                call.respond(ConnectStatusResponse(status = "pending", requestId = payload.requestId))
                            } else {
                                FileLogger.w("KtorServer", "POST /api/connect/request -> 409 Conflict (duplicate), id=${payload.requestId}")
                                call.respondText("Duplicate request", status = HttpStatusCode.Conflict)
                            }
                        } catch (e: Exception) {
                            FileLogger.e("KtorServer", "POST /api/connect/request error: ${e.message}", e)
                            call.respondText("Invalid request: ${e.message}", status = HttpStatusCode.BadRequest)
                        }
                    }
                }

                get("/api/connect/status/{requestId}") {
                    try {
                        val requestId = call.parameters["requestId"] ?: ""
                        val manager = connectionManager

                        if (manager == null || requestId.isEmpty()) {
                            call.respond(ConnectStatusResponse(status = "pending"))
                            return@get
                        }

                        val response = manager.getStatus(requestId)

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
                    } catch (e: Exception) {
                        FileLogger.e("KtorServer", "GET /api/connect/status error: ${e.message}", e)
                        call.respond(ConnectStatusResponse(status = "pending"))
                    }
                }

                post("/api/connect/response/{requestId}") {
                    withContext(Dispatchers.IO) {
                        try {
                            val requestId = call.parameters["requestId"] ?: ""
                            val manager = connectionManager

                            if (manager == null || requestId.isEmpty()) {
                                call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
                                return@withContext
                            }

                            val body = call.receive<ConnectResponseBody>()
                            val accepted = body.accepted

                            val response = manager.respondToRequest(requestId, accepted)

                            if (response != null) {
                                call.respond(response)
                            } else {
                                call.respondText("Request not found or already handled", status = HttpStatusCode.NotFound)
                            }
                        } catch (e: Exception) {
                            call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                        }
                    }
                }

                get("/api/app/{packageName}/{versionCode}") {
                    val packageName = call.parameters["packageName"] ?: ""
                    val versionCode = call.parameters["versionCode"]?.toLongOrNull() ?: 0L
                    FileLogger.i("KtorServer", "GET /api/app/$packageName/$versionCode")

                    val apps = appListProvider?.invoke() ?: emptyList()
                    val app = apps.find {
                        it.packageName == packageName && it.versionCode == versionCode
                    }

                    if (app == null) {
                        FileLogger.w("KtorServer", "GET /api/app/$packageName/$versionCode -> app NOT FOUND in local list (${apps.size} apps)")
                        call.respondText("App not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    if (!app.isExtractable) {
                        FileLogger.w("KtorServer", "GET /api/app/$packageName/$versionCode -> app NOT EXTRACTABLE (system/protected app)")
                        call.respondText("App is a system/protected app and cannot be extracted for transfer", status = HttpStatusCode.Forbidden)
                        return@get
                    }

                    FileLogger.d("KtorServer", "GET /api/app/$packageName/$versionCode -> packing...")
                    val packedFile = packer?.invoke(app)
                    if (packedFile?.exists() == true) {
                        FileLogger.i("KtorServer", "GET /api/app/$packageName/$versionCode -> sending ${packedFile.length()} bytes")
                        sendZipFile(call, packedFile, app.md5)
                    } else {
                        FileLogger.e("KtorServer", "GET /api/app/$packageName/$versionCode -> pack FAILED, file null or missing")
                        call.respondText("Packed file not found", status = HttpStatusCode.NotFound)
                    }
                }

                get("/api/download/{packageName}") {
                    val packageName = call.parameters["packageName"] ?: ""
                    FileLogger.i("KtorServer", "GET /api/download/$packageName (latest)")

                    val apps = appListProvider?.invoke() ?: emptyList()
                    val extractableApps = apps.filter { it.packageName == packageName && it.isExtractable }
                    val nonExtractable = apps.any { it.packageName == packageName && !it.isExtractable }

                    if (nonExtractable && extractableApps.isEmpty()) {
                        FileLogger.w("KtorServer", "GET /api/download/$packageName -> system/protected app, cannot extract")
                        call.respondText("App is a system/protected app and cannot be extracted for transfer", status = HttpStatusCode.Forbidden)
                        return@get
                    }

                    val app = extractableApps.maxByOrNull { it.versionCode }

                    if (app != null) {
                        try {
                            FileLogger.d("KtorServer", "GET /api/download/$packageName -> packing v${app.versionCode}...")
                            val packedFile = packer?.invoke(app)
                            if (packedFile?.exists() == true) {
                                FileLogger.i("KtorServer", "GET /api/download/$packageName -> sending ${packedFile.length()} bytes")
                                sendZipFile(call, packedFile, app.md5)
                            } else {
                                FileLogger.e("KtorServer", "GET /api/download/$packageName -> pack failed")
                                call.respondText("Failed to pack app", status = HttpStatusCode.InternalServerError)
                            }
                        } catch (e: Exception) {
                            FileLogger.e("KtorServer", "GET /api/download/$packageName -> exception: ${e.message}", e)
                            call.respondText("Error packing app: ${e.message}", status = HttpStatusCode.InternalServerError)
                        }
                    } else {
                        FileLogger.w("KtorServer", "GET /api/download/$packageName -> no matching app found")
                        call.respondText("App not found", status = HttpStatusCode.NotFound)
                    }
                }

                get("/api/download/{packageName}/{versionCode}") {
                    val packageName = call.parameters["packageName"] ?: ""
                    val versionCode = call.parameters["versionCode"]?.toLongOrNull() ?: 0L
                    FileLogger.i("KtorServer", "GET /api/download/$packageName/$versionCode")

                    val apps = appListProvider?.invoke() ?: emptyList()
                    val app = apps.find {
                        it.packageName == packageName && it.versionCode == versionCode
                    }

                    if (app == null) {
                        FileLogger.w("KtorServer", "GET /api/download/$packageName/$versionCode -> app NOT FOUND (total=${apps.size})")
                        call.respondText("App not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    if (!app.isExtractable) {
                        FileLogger.w("KtorServer", "GET /api/download/$packageName/$versionCode -> NOT EXTRACTABLE (system/protected)")
                        call.respondText("App is a system/protected app and cannot be extracted for transfer", status = HttpStatusCode.Forbidden)
                        return@get
                    }

                    try {
                        FileLogger.d("KtorServer", "GET /api/download/$packageName/$versionCode -> packing...")
                        val packedFile = packer?.invoke(app)
                        if (packedFile?.exists() == true) {
                            FileLogger.i("KtorServer", "GET /api/download/$packageName/$versionCode -> sending ${packedFile.length()} bytes, md5=${app.md5}")
                            sendZipFile(call, packedFile, app.md5)
                        } else {
                            FileLogger.e("KtorServer", "GET /api/download/$packageName/$versionCode -> pack returned null/missing")
                            call.respondText("Failed to pack app", status = HttpStatusCode.InternalServerError)
                        }
                    } catch (e: Exception) {
                        FileLogger.e("KtorServer", "GET /api/download/$packageName/$versionCode -> exception: ${e.message}", e)
                        call.respondText("Error packing app: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/api/disconnect") {
                        withContext(Dispatchers.IO) {
                            try {
                                val payload = call.receive<DisconnectPayload>()
                                val key = if (payload.identityKey.isNotEmpty()) payload.identityKey else payload.displayKey
                                FileLogger.i("KtorServer", "POST /api/disconnect from=$key")
                                disconnectHandler?.invoke(key)
                                call.respond(GenericStatusResponse())
                            } catch (e: Exception) {
                                FileLogger.e("KtorServer", "POST /api/disconnect error: ${e.message}", e)
                                call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
                            }
                        }
                    }

                    post("/api/refresh-applist") {
                        withContext(Dispatchers.IO) {
                            try {
                                val payload = call.receive<RefreshAppListPayload>()
                                val displayKey = payload.displayKey
                                if (displayKey.isNotEmpty()) {
                                    refreshAppListHandler?.invoke(displayKey)
                                }
                                call.respond(GenericStatusResponse())
                            } catch (e: Exception) {
                                call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
                            }
                        }
                    }

                    get("/api/deviceinfo") {
                    call.respond(DeviceInfoResponse(deviceName = android.os.Build.MODEL))
                }
            }
        }
        server = newServer
        newServer.start(wait = false)
        actualPort = newServer.resolvedConnectors().first().port
        return actualPort
    }

    private suspend fun sendZipFile(call: ApplicationCall, file: File, md5: String) {
        val actualMd5 = HashUtils.md5(file)
        FileLogger.d("KtorServer", "sendZipFile: storedMd5=${md5.take(8)}... actualPackedMd5=${actualMd5.take(8)}... size=${file.length()} file=${file.name}")
        call.response.header("X-MD5", actualMd5)
        call.response.header("X-File-Size", file.length().toString())
        call.response.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
        val isSingleApk = file.name.endsWith(".apk") && !file.name.endsWith(".apks")
        val contentType = if (isSingleApk) ContentType.Application.OctetStream else ContentType.Application.Zip
        call.respondOutputStream(contentType) {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    write(buffer, 0, bytesRead)
                }
            }
            flush()
        }
    }



    fun stop() {
        server?.stop(1000, 5000)
        server = null
        actualPort = 0
    }

    fun isRunning(): Boolean {
        return server != null
    }

    fun getPort(): Int {
        return actualPort
    }
}
