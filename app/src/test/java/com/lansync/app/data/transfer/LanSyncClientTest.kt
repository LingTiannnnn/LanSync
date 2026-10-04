package com.lansync.app.data.transfer

import com.lansync.app.data.HashUtils
import com.lansync.app.data.model.AppInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** 裸 body（无 X-MD5 头之外的编排），用于构造 `contentLength()` 与实际字节数不符的响应。 */
    private fun rawResponse(code: Int, body: ResponseBody?, vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("http://1.1.1.1:1/api/download/com.a/1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .body(body)
        headers.forEach { builder.header(it.first, it.second) }
        return builder.build()
    }

    /** [declaredLength] 为 -1 表示「服务端未告知长度」，可小于实际字节数以触发 coerceIn 分支。 */
    private fun declaredLengthBody(bytes: ByteArray, declaredLength: Long): ResponseBody =
        object : ResponseBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = declaredLength
            override fun source(): BufferedSource = Buffer().write(bytes)
        }

    private fun appInfo(
        pkg: String,
        versionCode: Long,
        split: Boolean,
        paths: List<String>,
        md5: String
    ) = AppInfo(pkg, pkg, "1.0", versionCode, paths, md5, true, 1L, isSplitApk = split)

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

    // ---------- DL-7（并覆盖 DL-6 的可达形态）：空落盘 ----------
    //
    // DL-6「空 body → Empty response body」在 OkHttp 4.12 下**不可覆盖**：Response.body 的实际取值永不为 null
    // （强行 body(null) 构造出的响应会在 Response.close() 里 NPE，被外层 catch 归一为 "Download failed"）。
    // 服务端「200 但无内容」的真实形态是 0 长度 body，走下面这条分支。详见 TEST-PLAN §4。

    @Test
    fun `download zero length file errors and leaves no residue (DL-7, DL-6 reachable form)`() = runTest {
        val empty = ByteArray(0)
        // 故意带上「空内容的正确 X-MD5」：若空文件检查缺失，本例会误判为 Success
        val resp = buildResponse(
            200, empty,
            "X-MD5" to md5Of(empty),
            "Content-Disposition" to "attachment; filename=\"com_a_1.apk\""
        )
        val downloads = tmp.newFolder("dl7_empty_file")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Error)
        assertEquals("Downloaded file is empty", (result as LanSyncClient.DownloadResult.Error).message)
        // 行为改进：0 字节残留必须清掉，否则会被 getDownloadedFiles() 列进文件页
        assertFalse(File(downloads, "com_a_1.apk").exists())
        assertTrue(client.getDownloadedFiles().isEmpty())
    }

    // ---------- DL-8 / DL-9：AppPacker → X-MD5 → LanSyncClient 端到端 ----------

    @Test
    fun `single apk end to end verifies with list md5 (DL-8)`() = runTest {
        val src = tmp.newFile("dl8_src.apk").apply { writeBytes("SINGLE_APK_PAYLOAD".toByteArray()) }
        val listMd5 = HashUtils.md5(src)!!                      // 列表内 AppInfo.md5 语义
        val artifact = AppPacker(tmp.newFolder("dl8_pack")).packApp(
            appInfo("com.a", 1, split = false, paths = listOf(src.absolutePath), md5 = listMd5)
        )!!

        // 服务端对产物实时计算 X-MD5（D1）+ Content-Disposition 带产物名（SPEC §5.1）
        val resp = buildResponse(
            200, artifact.readBytes(),
            "X-MD5" to HashUtils.md5(artifact)!!,
            "Content-Disposition" to "attachment; filename=\"${artifact.name}\""
        )
        val downloads = tmp.newFolder("dl8_dl")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1)

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        val file = (result as LanSyncClient.DownloadResult.Success).file
        assertEquals("com_a_1.apk", file.name)
        assertEquals(src.length(), file.length())               // 单包 = 源字节副本（SPEC §5.3）
        // 单包场景两条轨重合：产物哈希 == 列表 md5 == 落盘哈希（SPEC §8.1）
        assertEquals(listMd5, HashUtils.md5(file))
        assertEquals(HashUtils.md5(artifact), HashUtils.md5(file))
    }

    @Test
    fun `split apk end to end verifies by artifact hash not list md5 (DL-9)`() = runTest {
        val base = tmp.newFile("dl9_base.apk").apply { writeBytes("BASE_BYTES".toByteArray()) }
        val split = tmp.newFile("dl9_split.apk").apply { writeBytes("SPLIT_BYTES".toByteArray()) }
        val paths = listOf(base.absolutePath, split.absolutePath)
        val listMd5 = HashUtils.md5(paths)!!                    // 源拼接摘要 = 列表内 md5 语义
        val artifact = AppPacker(tmp.newFolder("dl9_pack")).packApp(
            appInfo("com.a", 7, split = true, paths = paths, md5 = listMd5)
        )!!
        val artifactMd5 = HashUtils.md5(artifact)!!
        // SPEC §8.1：zip 产物哈希 != 原始拼接摘要 —— 这正是 D1 的前提
        assertNotEquals(listMd5, artifactMd5)

        // 以 X-MD5（产物哈希）校验 → 通过
        val okResp = buildResponse(
            200, artifact.readBytes(),
            "X-MD5" to artifactMd5,
            "Content-Disposition" to "attachment; filename=\"${artifact.name}\""
        )
        val okDir = tmp.newFolder("dl9_ok")
        val okResult = LanSyncClient(downloadsDir = okDir, downloadClient = mockClientReturning(okResp))
            .downloadApksFile("1.1.1.1", 1, "com.a", 7)

        assertTrue(okResult is LanSyncClient.DownloadResult.Success)
        assertEquals("com_a_7.apks", (okResult as LanSyncClient.DownloadResult.Success).file.name)
        assertEquals(artifactMd5, HashUtils.md5(okResult.file))

        // 同一产物若按列表 md5（= 旧 expectedMd5 兜底轨）校验 → 必失败，证明 D1 必要性
        val staleResp = buildResponse(
            200, artifact.readBytes(),
            "X-MD5" to listMd5,
            "Content-Disposition" to "attachment; filename=\"${artifact.name}\""
        )
        val staleDir = tmp.newFolder("dl9_stale")
        val staleResult = LanSyncClient(downloadsDir = staleDir, downloadClient = mockClientReturning(staleResp))
            .downloadApksFile("1.1.1.1", 1, "com.a", 7)

        assertTrue(staleResult is LanSyncClient.DownloadResult.Error)
        assertEquals("MD5 verification failed", (staleResult as LanSyncClient.DownloadResult.Error).message)
        assertFalse(File(staleDir, "com_a_7.apks").exists())
    }

    // ---------- DL-10：进度回调 ----------

    @Test
    fun `progress is monotonic and reaches 100 when length known (DL-10)`() = runTest {
        // 缓冲区 64KB，200KB 内容 → 必然多次回调
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val resp = buildResponse(
            200, content,
            "X-MD5" to md5Of(content),
            "X-File-Size" to content.size.toString()
        )
        val downloads = tmp.newFolder("dl10_monotonic")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))
        val percents = mutableListOf<Int>()

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1) { percents.add(it) }

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        assertTrue("回调次数过少（${percents.size}），无法验证单调性", percents.size >= 3)
        assertEquals(100, percents.last())
        assertTrue("percent 非单调：$percents", percents.zipWithNext().all { (prev, next) -> next >= prev })
        assertTrue("percent 越界：$percents", percents.all { it in 0..100 })
    }

    @Test
    fun `progress not reported when content length unknown (DL-10)`() = runTest {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        // contentLength() = -1 且无 X-File-Size 头 → contentLength 归为 -1，不得回调
        val resp = rawResponse(
            200, declaredLengthBody(content, -1L),
            "X-MD5" to md5Of(content)
        )
        val downloads = tmp.newFolder("dl10_unknown_length")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))
        val percents = mutableListOf<Int>()

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1) { percents.add(it) }

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        assertEquals(content.size.toLong(), (result as LanSyncClient.DownloadResult.Success).file.length())
        assertTrue("长度未知时不应回调进度：$percents", percents.isEmpty())
    }

    @Test
    fun `progress is coerced into 0 to 100 when server underreports size (DL-10)`() = runTest {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        // 声明长度只有实际的一半 → 裸算会得到 131/196/200，必须被 coerceIn 夹到 100
        val resp = rawResponse(
            200, declaredLengthBody(content, content.size / 2L),
            "X-MD5" to md5Of(content)
        )
        val downloads = tmp.newFolder("dl10_coerce")
        val client = LanSyncClient(downloadsDir = downloads, downloadClient = mockClientReturning(resp))
        val percents = mutableListOf<Int>()

        val result = client.downloadApksFile("1.1.1.1", 1, "com.a", 1) { percents.add(it) }

        assertTrue(result is LanSyncClient.DownloadResult.Success)
        assertTrue("回调次数过少（${percents.size}）", percents.size >= 3)
        assertEquals(100, percents.last())
        assertTrue("percent 越界（coerceIn 失效）：$percents", percents.all { it in 0..100 })
        assertTrue("percent 非单调：$percents", percents.zipWithNext().all { (prev, next) -> next >= prev })
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
