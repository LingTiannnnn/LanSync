package com.lansync.app.data.server

import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectRequestPayload
import com.lansync.app.data.model.ConnectResponsePayload
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LanSyncRouting 路由集成测试（ktor-server-test-host，TEST-PLAN.md §3）。
 *
 * 覆盖 SPEC.md §3 全 10 路由的状态码矩阵、§4 Content-Type、§5.1 下载三响应头、
 * §1.2.1 encodeDefaults=false 省略行为、§8.3 决策 D1（X-MD5 = 打包产物哈希）。
 * appList provider / packer / pairing 全部用 Fake，不接扫描、不接 UI（Phase 1 骨架）。
 */
class LanSyncRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val delegate = FakeDelegate()
    private val pairing = FakePairing()

    private fun app(
        pkg: String,
        vc: Long,
        extractable: Boolean = true,
        split: Boolean = false
    ) = AppInfo(
        packageName = pkg,
        appName = pkg,
        versionName = "1.0",
        versionCode = vc,
        sourcePaths = emptyList(),
        md5 = "listmd5",
        isExtractable = extractable,
        fileSize = 1L,
        isSystemApp = false,
        isSplitApk = split
    )

    private fun packed(name: String, content: String): File =
        tmp.newFile(name).apply { writeText(content) }

    private fun String.compact() = replace(Regex("\\s"), "")

    // ---------- #1 ping ----------
    @Test
    fun `ping returns pong`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/ping")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("pong"))
        assertTrue(r.bodyAsText().contains("status"))
    }

    // ---------- #2 applist ----------
    @Test
    fun `applist returns apps`() = testApplication {
        delegate.apps = listOf(app("com.a", 1), app("com.b", 2))
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/applist")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("com.a"))
        assertTrue(body.contains("com.b"))
        // ContentNegotiation 会附加 charset=UTF-8，比较时去除参数
        assertEquals(ContentType.Application.Json, r.contentType()?.withoutParameters())
    }

    @Test
    fun `applist empty returns empty array`() = testApplication {
        delegate.apps = emptyList()
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/applist")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("[]", r.bodyAsText().compact())
    }

    // ---------- #3 deviceinfo ----------
    @Test
    fun `deviceinfo returns name without version field`() = testApplication {
        delegate.name = "PixelTest"
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/deviceinfo")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("PixelTest"))
        // SPEC §2.9 / §1.2.1：version 为死字段，encodeDefaults=false 下不上线
        assertFalse(body.contains("version"))
    }

    // ---------- #4 connect/request ----------
    @Test
    fun `connectRequest new returns pending`() = testApplication {
        pairing.receiveResult = true
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"r1","requesterName":"A","requesterIp":"1.1.1.1","requesterPort":1,"timestamp":1}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("pending"))
        assertTrue(body.contains("r1"))
    }

    @Test
    fun `connectRequest duplicate returns 409`() = testApplication {
        pairing.receiveResult = false
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"r1","requesterName":"A","requesterIp":"1.1.1.1","requesterPort":1,"timestamp":1}""")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("CONFLICT"))
    }

    @Test
    fun `connectRequest bad json returns 400`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"not":"valid"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("INVALID_REQUEST"))
        // ARCHITECTURE §7：不回显 e.message
        assertFalse(r.bodyAsText().contains("Exception"))
    }

    // ---------- #5 connect/status ----------
    @Test
    fun `connectStatus pending when store returns null`() = testApplication {
        pairing.statusResult = null
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/connect/status/r1")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("pending"))
    }

    @Test
    fun `connectStatus accepted`() = testApplication {
        pairing.statusResult = ConnectResponsePayload("r1", true, "Responder", "Accepted")
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/connect/status/r1")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("accepted"))
        assertTrue(body.contains("Responder"))
    }

    @Test
    fun `connectStatus rejected`() = testApplication {
        pairing.statusResult = ConnectResponsePayload("r1", false, "Responder", "Rejected")
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/connect/status/r1")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("rejected"))
    }

    @Test
    fun `connectStatus empty id returns pending`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/connect/status/")
        // 空 requestId → 失败安全 pending（SPEC §2.6）。路径尾斜杠可能 404 或匹配空参，二者皆可接受
        assertTrue(
            r.status == HttpStatusCode.OK && r.bodyAsText().contains("pending") ||
                r.status == HttpStatusCode.NotFound
        )
    }

    // ---------- #6 connect/response ----------
    @Test
    fun `connectResponse ok returns 200`() = testApplication {
        pairing.respondResult = ConnectResponsePayload("r1", true, "Responder", "Connection accepted")
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/response/r1") {
            contentType(ContentType.Application.Json)
            setBody("""{"accepted":true}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("Connection accepted"))
    }

    @Test
    fun `connectResponse not found returns 404`() = testApplication {
        pairing.respondResult = null
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/response/r1") {
            contentType(ContentType.Application.Json)
            setBody("""{"accepted":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("REQUEST_NOT_FOUND"))
    }

    @Test
    fun `connectResponse bad json returns 400`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/connect/response/r1") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("INVALID_REQUEST"))
    }

    // ---------- #7 download/{pkg} ----------
    @Test
    fun `download single apk returns octet-stream with headers`() = testApplication {
        val f = packed("com_a_100.apk", "APKBYTES")
        delegate.apps = listOf(app("com.a", 100))
        delegate.packedFile = f
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("application/octet-stream", r.headers[HttpHeaders.ContentType])
        assertEquals(HashUtils.md5(f), r.headers["X-MD5"])
        assertEquals(f.length().toString(), r.headers["X-File-Size"])
        assertTrue(r.headers[HttpHeaders.ContentDisposition]!!.contains("com_a_100.apk"))
        assertArrayEquals("APKBYTES".toByteArray(), r.readBytes())
    }

    @Test
    fun `download split apks returns zip content type`() = testApplication {
        val f = packed("com_a_100.apks", "ZIPBYTES")
        delegate.apps = listOf(app("com.a", 100, split = true))
        delegate.packedFile = f
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("application/zip", r.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `download selects highest versionCode`() = testApplication {
        val f = packed("com_a_200.apk", "X")
        delegate.apps = listOf(app("com.a", 100), app("com.a", 200))
        delegate.packedFile = f
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(200L, delegate.lastPackedApp?.versionCode)
    }

    @Test
    fun `download all non-extractable returns 403`() = testApplication {
        delegate.apps = listOf(app("com.sys", 1, extractable = false))
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.sys")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("NOT_EXTRACTABLE"))
    }

    @Test
    fun `download no match returns 404`() = testApplication {
        delegate.apps = listOf(app("com.a", 1))
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.missing")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("APP_NOT_FOUND"))
    }

    @Test
    fun `download pack null returns 500`() = testApplication {
        delegate.apps = listOf(app("com.a", 1))
        delegate.packedFile = null
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertTrue(r.bodyAsText().contains("PACK_FAILED"))
    }

    @Test
    fun `download pack throws returns 500 without leaking message`() = testApplication {
        delegate.apps = listOf(app("com.a", 1))
        delegate.packThrows = true
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("PACK_FAILED"))
        assertFalse(body.contains("boom"))
    }

    // ---------- #8 download/{pkg}/{vc} ----------
    @Test
    fun `downloadVersioned exact match returns 200`() = testApplication {
        val f = packed("com_a_100.apks", "Z")
        delegate.apps = listOf(app("com.a", 100, split = true))
        delegate.packedFile = f
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a/100")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(HashUtils.md5(f), r.headers["X-MD5"])
        assertEquals("application/zip", r.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `downloadVersioned no match returns 404`() = testApplication {
        delegate.apps = listOf(app("com.a", 100))
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a/999")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("APP_NOT_FOUND"))
    }

    @Test
    fun `downloadVersioned not extractable returns 403`() = testApplication {
        delegate.apps = listOf(app("com.a", 100, extractable = false))
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a/100")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("NOT_EXTRACTABLE"))
    }

    @Test
    fun `downloadVersioned invalid version code returns 404`() = testApplication {
        delegate.apps = listOf(app("com.a", 100))
        application { lanSyncModule(delegate, pairing) }
        // 非法 vc → toLongOrNull()?:0L → 无 vc=0 匹配 → 404（SPEC §3.1 #8）
        val r = client.get("/api/download/com.a/abc")
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `downloadVersioned pack null returns 500`() = testApplication {
        delegate.apps = listOf(app("com.a", 100))
        delegate.packedFile = null
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a/100")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertTrue(r.bodyAsText().contains("PACK_FAILED"))
    }

    @Test
    fun `download X-MD5 equals packed file hash not list md5 (D1)`() = testApplication {
        val f = packed("com_a_100.apk", "REAL_PRODUCT_BYTES")
        // 列表内 md5 与产物哈希必然不同（SPEC §8.1）；上线的 X-MD5 必须是产物哈希
        delegate.apps = listOf(app("com.a", 100).copy(md5 = "totally_different_list_md5"))
        delegate.packedFile = f
        application { lanSyncModule(delegate, pairing) }
        val r = client.get("/api/download/com.a/100")
        assertEquals(HashUtils.md5(f), r.headers["X-MD5"])
        assertFalse(r.headers["X-MD5"] == "totally_different_list_md5")
    }

    // ---------- #9 disconnect ----------
    @Test
    fun `disconnect ok returns empty object and identityKey priority`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/disconnect") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayKey":"1.1.1.1:5","identityKey":"uuid-9"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("{}", r.bodyAsText().compact())
        assertEquals("uuid-9", delegate.lastDisconnectKey)
    }

    @Test
    fun `disconnect falls back to displayKey when identityKey empty`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/disconnect") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayKey":"1.1.1.1:5"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("1.1.1.1:5", delegate.lastDisconnectKey)
    }

    @Test
    fun `disconnect bad json returns 400`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/disconnect") {
            contentType(ContentType.Application.Json)
            setBody("""{"wrong":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("INVALID_REQUEST"))
    }

    // ---------- #10 refresh-applist ----------
    @Test
    fun `refresh ok returns empty object and calls handler`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/refresh-applist") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayKey":"2.2.2.2:7"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("{}", r.bodyAsText().compact())
        assertEquals("2.2.2.2:7", delegate.lastRefreshKey)
    }

    @Test
    fun `refresh empty displayKey does not call handler`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/refresh-applist") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayKey":""}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertNull(delegate.lastRefreshKey)
    }

    @Test
    fun `refresh bad json returns 400`() = testApplication {
        application { lanSyncModule(delegate, pairing) }
        val r = client.post("/api/refresh-applist") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("INVALID_REQUEST"))
    }

    // ---------- Fakes ----------
    private class FakeDelegate : ServerApiDelegate {
        var apps: List<AppInfo> = emptyList()
        var packedFile: File? = null
        var packThrows: Boolean = false
        var lastPackedApp: AppInfo? = null
        var lastDisconnectKey: String? = null
        var lastRefreshKey: String? = null
        var name: String = "TestServer"
        override fun provideAppList(): List<AppInfo> = apps
        override suspend fun pack(app: AppInfo): File? {
            if (packThrows) throw RuntimeException("boom-secret-detail")
            lastPackedApp = app
            return packedFile
        }
        override fun onDisconnect(key: String) { lastDisconnectKey = key }
        override fun onRefreshAppList(displayKey: String) { lastRefreshKey = displayKey }
        override fun deviceName(): String = name
    }

    private class FakePairing : PairingStore {
        var receiveResult: Boolean = true
        var statusResult: ConnectResponsePayload? = null
        var respondResult: ConnectResponsePayload? = null
        override fun receiveRequest(payload: ConnectRequestPayload): Boolean = receiveResult
        override fun getStatus(requestId: String): ConnectResponsePayload? = statusResult
        override fun respondToRequest(requestId: String, accepted: Boolean): ConnectResponsePayload? = respondResult
    }
}
