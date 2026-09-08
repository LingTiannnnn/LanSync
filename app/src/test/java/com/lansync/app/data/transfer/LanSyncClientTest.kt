package com.lansync.app.data.transfer

import com.lansync.app.data.HashUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LanSyncClient 测试（TEST-PLAN.md §4 下载+MD5 校验链路 + 连接状态解析）。
 *
 * 用 MockK mock OkHttpClient/Call，返回**真实构造**的 okhttp3.Response（含/不含 X-MD5 头），
 * 无网络、无新增依赖。重点验证决策 D1（SPEC.md §8.3）：X-MD5 唯一权威、缺头即失败、不匹配删文件。
 */
class LanSyncClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildResponse(code: Int, body: ByteArray, vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("http://1.1.1.1:1/api/download/com.a/1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
        headers.forEach { builder.header(it.first, it.second) }
        return builder.build()
    }

    private fun mockClientReturning(response: Response): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns response
        val c = mockk<OkHttpClient>()
        every { c.newCall(any()) } returns call
        return c
    }

    private fun md5Of(content: ByteArray): String {
        val ref = File(tmp.root, "ref_${System.nanoTime()}.bin").apply { writeBytes(content) }
        return HashUtils.md5(ref)!!
    }

    // ---------- 下载 + MD5 (D1) ----------

    @Test
    fun `download success when X-MD5 matches product`() = runTest {
        val content = "PRODUCT_BYTES".toByteArray()
        val md5 = md5Of(content)
        val resp = buildResponse(
            200, content,
            "X-MD5" to md5,
            "Content-Disposition" to "attachment; filename=\"com_a_1.apk\"",
            "X-File-Size" to content.size.toString()
        )
        val downloads = tmp.newFolder("dl1")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        val file = (result as LanSyncClient.DownloadResult.Success).file
        assertEquals("com_a_1.apk", file.name)
        assertTrue(file.exists())
        assertEquals(md5, HashUtils.md5(file))
    }

    @Test
    fun `download mismatch deletes file and errors`() = runTest {
        val content = "PRODUCT_BYTES".toByteArray()
        val resp = buildResponse(
            200, content,
            "X-MD5" to "deadbeefdeadbeefdeadbeefdeadbeef",
            "Content-Disposition" to "attachment; filename=\"com_a_1.apk\""
        )
        val downloads = tmp.newFolder("dl2")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Error)
        assertEquals("MD5 verification failed", (result as LanSyncClient.DownloadResult.Error).message)
        assertFalse(File(downloads, "com_a_1.apk").exists())
    }

    @Test
    fun `download missing X-MD5 header fails (D1 no fallback)`() = runTest {
        val content = "PRODUCT_BYTES".toByteArray()
        // 无 X-MD5 头：新语义下直接失败并删除文件，绝不用 expectedMd5 兜底（SPEC §8.3）
        val resp = buildResponse(
            200, content,
            "Content-Disposition" to "attachment; filename=\"com_a_1.apk\""
        )
        val downloads = tmp.newFolder("dl3")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Error)
        assertTrue((result as LanSyncClient.DownloadResult.Error).message.contains("Missing X-MD5"))
        assertFalse(File(downloads, "com_a_1.apk").exists())
    }

    @Test
    fun `download http error surfaces code`() = runTest {
        val resp = buildResponse(404, "not found".toByteArray())
        val downloads = tmp.newFolder("dl4")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.none", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Error)
        assertTrue((result as LanSyncClient.DownloadResult.Error).message.contains("404"))
    }

    @Test
    fun `download uses default filename when no content-disposition`() = runTest {
        val content = "BYTES".toByteArray()
        val md5 = md5Of(content)
        val resp = buildResponse(200, content, "X-MD5" to md5)
        val downloads = tmp.newFolder("dl5")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 7)

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        // downloadApksFile 默认名 = {pkg_}_{vc}.apks（SPEC §5.3）
        assertEquals("com_a_7.apks", (result as LanSyncClient.DownloadResult.Success).file.name)
    }

    @Test
    fun `download latest uses default name without version`() = runTest {
        val content = "BYTES".toByteArray()
        val md5 = md5Of(content)
        val resp = buildResponse(200, content, "X-MD5" to md5)
        val downloads = tmp.newFolder("dl6")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadLatestApksFile("1.1.1.1", 1, "com.a")

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        assertEquals("com_a.apks", (result as LanSyncClient.DownloadResult.Success).file.name)
    }

    // ---------- 连接状态解析 (SPEC §2.6) ----------

    @Test
    fun `parseConnectStatus pending returns null`() {
        val client = LanSyncClient(tmp.root)
        assertNull(client.parseConnectStatus("""{"status":"pending"}"""))
    }

    @Test
    fun `parseConnectStatus accepted returns responder`() {
        val client = LanSyncClient(tmp.root)
        val r = client.parseConnectStatus("""{"status":"accepted","responderName":"B"}""")
        assertTrue(r is LanSyncClient.ConnectResult.Accepted)
        assertEquals("B", (r as LanSyncClient.ConnectResult.Accepted).responderName)
    }

    @Test
    fun `parseConnectStatus rejected returns message`() {
        val client = LanSyncClient(tmp.root)
        val r = client.parseConnectStatus("""{"status":"rejected","message":"Timeout"}""")
        assertTrue(r is LanSyncClient.ConnectResult.Rejected)
        assertEquals("Timeout", (r as LanSyncClient.ConnectResult.Rejected).message)
    }

    @Test
    fun `parseConnectStatus unknown returns null`() {
        val client = LanSyncClient(tmp.root)
        assertNull(client.parseConnectStatus("""{"status":"weird"}"""))
    }

    // ---------- fetchAppList ----------

    @Test
    fun `fetchAppList decodes json array`() = runTest {
        val json = """[{"packageName":"com.a","appName":"A","versionName":"1","versionCode":1,"sourcePaths":[],"md5":"m","isExtractable":true,"fileSize":1}]"""
        val resp = buildResponse(200, json.toByteArray())
        val client = LanSyncClient(downloadsDir = tmp.root, client = mockClientReturning(resp))

        val apps = client.fetchAppList("1.1.1.1", 1)

        assertNotNull(apps)
        assertEquals(1, apps!!.size)
        assertEquals("com.a", apps[0].packageName)
    }

    @Test
    fun `fetchAppList non-success returns null`() = runTest {
        val resp = buildResponse(500, "err".toByteArray())
        val client = LanSyncClient(downloadsDir = tmp.root, client = mockClientReturning(resp))
        assertNull(client.fetchAppList("1.1.1.1", 1))
    }
}
