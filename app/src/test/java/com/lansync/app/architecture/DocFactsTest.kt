package com.lansync.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文档事实门禁（CONVENTIONS §6「写进文档的每个数字都要先对代码实测」）——此前只靠人工核对。
 *
 * 零新依赖，直接跑在 `testDebugUnitTest` 门禁里。canonical truth 只取仓库内可实测来源：
 * Gradle 构建脚本、测试源码、`.git` 引用。`.qoder/agents` 下的镜像文件在版本控制之外，只报告差异不改写。
 * STATUS §1 的阶段验收数字是各阶段自己的基线，不属本门禁范围。
 */
class DocFactsTest {

    private val repoRoot: File = listOf(".", "..")
        .map(::File)
        .firstOrNull { File(it, "docs").isDirectory && File(it, "app").isDirectory }
        ?.canonicalFile
        ?: error("找不到仓库根（需同时含 docs/ 与 app/），工作目录=${System.getProperty("user.dir")}")

    private fun file(relativePath: String): File = File(repoRoot, relativePath)

    private fun read(relativePath: String): String {
        val target = file(relativePath)
        assertTrue("缺少文件：${target.path}", target.isFile)
        return target.readText(Charsets.UTF_8)
    }

    /** §3 版本锁表所在段落，取到下一个二级标题为止。 */
    private fun section(text: String, heading: String): String {
        val start = text.indexOf(heading)
        assertTrue("找不到段落标题「$heading」", start >= 0)
        val end = text.indexOf("\n## ", start + heading.length)
        return if (end < 0) text.substring(start) else text.substring(start, end)
    }

    private fun gradleScriptText(): String = listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "app/build.gradle.kts",
        "gradle/wrapper/gradle-wrapper.properties",
    ).joinToString("\n") { read(it) }

    private val versionToken = Regex("""\d+\.\d+(?:\.\d+)*""")

    /** canonical 来源是本机 JDK 安装而非仓库，不参与构建脚本对照。 */
    private val machineLevelLiterals = setOf("17.0.20.1", "17.0.20.101")

    private fun containsVersion(haystack: String, token: String): Boolean =
        Regex("""(?<![\d.])${Regex.escape(token)}(?![\d.])""").containsMatchIn(haystack)

    /** 排除 `1.1.x` 这类开区间写法：它不是可对照的字面量。 */
    private fun versionLiterals(text: String): List<String> =
        versionToken.findAll(text)
            .filter { !text.substring(it.range.last + 1).startsWith(".x") }
            .map { it.value }
            .filter { it !in machineLevelLiterals }
            .distinct()
            .toList()

    private fun measuredTestFiles(): List<File> =
        file("app/src/test").walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .toList()

    private fun measuredCaseCount(testFile: File): Int =
        testFile.readLines().count { it.trim().startsWith("@Test") }

    private fun measuredMainSourceCount(): Int {
        val mainSrc = file("app/src/main/java")
        assertTrue("找不到 app/src/main/java", mainSrc.isDirectory)
        val count = mainSrc.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("扫到 0 个生产源文件，检查已失效", count > 0)
        return count
    }

    private fun syncClaimRegex(): Regex = Regex(
        "`?origin/main`?\\s*(?:已)?与\\s*`?main`?\\s*同步|`?main`?\\s*(?:已)?与\\s*`?origin/main`?\\s*同步"
    )

    private fun resolveRef(ref: String): String? {
        if (!file(".git").isDirectory) return null
        val loose = file(".git/$ref")
        if (loose.isFile) return loose.readText().trim().ifEmpty { null }
        val packed = file(".git/packed-refs")
        if (!packed.isFile) return null
        return packed.readLines()
            .firstOrNull { !it.startsWith("#") && it.endsWith(" $ref") }
            ?.substringBefore(' ')?.trim()
    }

    private fun mirrors(): List<File> = listOf("lansync-explore.md", "lansync-review.md")
        .map { file(".qoder/agents/$it") }
        .filter { it.isFile }

    @Test
    fun `version lock table literals all resolve in gradle scripts`() {
        val literals = versionLiterals(section(read("docs/BUILD.md"), "## 3. 技术栈与版本锁"))
        assertTrue("§3 未提取到任何版本字面量，检查已失效", literals.isNotEmpty())

        val scripts = gradleScriptText()
        val unresolved = literals.filter { !containsVersion(scripts, it) }
        assertEquals(
            "docs/BUILD.md §3 的版本字面量在构建脚本里找不到（文档漂移）：\n" +
                unresolved.joinToString("\n") { "  $it" },
            0,
            unresolved.size,
        )
    }

    @Test
    fun `sdk and jvm target rows match app build script`() {
        val appScript = read("app/build.gradle.kts")

        fun measured(pattern: String): String =
            Regex(pattern).find(appScript)?.groupValues?.get(1)
                ?: error("app/build.gradle.kts 里找不到 $pattern")

        val buildDoc = read("docs/BUILD.md")
        val sdkRow = buildDoc.lines().firstOrNull { it.startsWith("| SDK |") }
            ?: error("docs/BUILD.md §3 找不到 SDK 行")
        for (key in listOf("minSdk", "targetSdk", "compileSdk")) {
            val value = measured("$key\\s*=\\s*(\\d+)")
            assertTrue(
                "docs/BUILD.md SDK 行与 app/build.gradle.kts 的 $key = $value 不符：$sdkRow",
                sdkRow.contains("$key $value"),
            )
        }

        val jvmTarget = measured("jvmTarget\\s*=\\s*\"(\\d+)\"")
        val jvmRow = buildDoc.lines().firstOrNull { it.startsWith("| 语言 / JVM |") }
            ?: error("docs/BUILD.md §3 找不到「语言 / JVM」行")
        assertTrue(
            "docs/BUILD.md「语言 / JVM」行与 app/build.gradle.kts 的 jvmTarget = $jvmTarget 不符：$jvmRow",
            jvmRow.contains("jvmTarget = $jvmTarget"),
        )
    }

    @Test
    fun `documented test baseline matches measured test sources`() {
        val files = measuredTestFiles()
        assertTrue("扫到 0 个测试文件，检查已失效", files.isNotEmpty())
        val fileCount = files.size
        val caseCount = files.sumOf { measuredCaseCount(it) }
        assertTrue("实测 @Test 数为 0，检查已失效", caseCount > 0)

        val status = read("docs/STATUS.md")
        val green = Regex("""(\d+)/(\d+)\s*全绿""").find(status)
            ?: error("docs/STATUS.md §2 找不到「N/N 全绿」基线")
        assertEquals("docs/STATUS.md §2 已通过的例数与实测 @Test 数不符", caseCount, green.groupValues[1].toInt())
        assertEquals("docs/STATUS.md §2 的总例数与实测 @Test 数不符", caseCount, green.groupValues[2].toInt())
        val statusFiles = Regex("""(\d+)\s*个测试文件""").find(status)
            ?: error("docs/STATUS.md §2 找不到「N 个测试文件」")
        assertEquals("docs/STATUS.md §2 的测试文件数与实测不符", fileCount, statusFiles.groupValues[1].toInt())

        val planHeader = Regex("""(\d+)\s*个测试文件\s*/\s*(\d+)\s*例""")
            .find(section(read("docs/TEST-PLAN.md"), "## 10. 当前落地清单"))
            ?: error("docs/TEST-PLAN.md §10 找不到「N 个测试文件 / M 例」")
        assertEquals("docs/TEST-PLAN.md §10 的测试文件数与实测不符", fileCount, planHeader.groupValues[1].toInt())
        assertEquals("docs/TEST-PLAN.md §10 的例数与实测不符", caseCount, planHeader.groupValues[2].toInt())

        val drift = mutableListOf<String>()
        for (mirror in mirrors()) {
            val match = Regex("""(\d+)\s*文件\s*(\d+)\s*例""").find(mirror.readText(Charsets.UTF_8)) ?: continue
            if (match.groupValues[1].toInt() != fileCount) {
                drift += "  ${mirror.name}: 文档 ${match.groupValues[1]} 文件，实测 $fileCount"
            }
            if (match.groupValues[2].toInt() != caseCount) {
                drift += "  ${mirror.name}: 文档 ${match.groupValues[2]} 例，实测 $caseCount"
            }
        }
        assertEquals(".qoder/agents 镜像的测试基线漂移：\n" + drift.joinToString("\n"), 0, drift.size)
    }

    @Test
    fun `per file case counts in test plan match measured`() {
        val measured = measuredTestFiles().associate { it.nameWithoutExtension to measuredCaseCount(it) }
        val rows = Regex("""^\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|""", RegexOption.MULTILINE)
            .findAll(section(read("docs/TEST-PLAN.md"), "## 10. 当前落地清单"))
            .map { it.groupValues[1].substringAfterLast('/') to it.groupValues[2].toInt() }
            .toList()
        assertTrue("docs/TEST-PLAN.md §10 未解析到任何用例行，检查已失效", rows.isNotEmpty())

        val drift = rows.mapNotNull { (name, documented) ->
            val actual = measured[name]
            when {
                actual == null -> "  $name: 表里有，app/src/test 下却找不到该测试文件"
                actual != documented -> "  $name: 文档 $documented 例，实测 $actual 例"
                else -> null
            }
        }
        val unlisted = measured.keys - rows.map { it.first }.toSet()
        assertEquals(
            "docs/TEST-PLAN.md §10 用例数漂移：\n" + (drift + unlisted.sorted().map { "  $it: 实测存在但表里未登记" })
                .joinToString("\n"),
            0,
            drift.size + unlisted.size,
        )
    }

    @Test
    fun `mirrored main source file count matches measured`() {
        val measured = measuredMainSourceCount()
        val drift = mutableListOf<String>()
        for (mirror in mirrors()) {
            for (match in Regex("""(\d+)\s*个\s*`?\.kt`?""").findAll(mirror.readText(Charsets.UTF_8))) {
                if (match.groupValues[1].toInt() != measured) {
                    drift += "  ${mirror.name}: 文档 ${match.groupValues[1]} 个 .kt，实测 $measured"
                }
            }
        }
        assertEquals(".qoder/agents 镜像的生产源文件数漂移：\n" + drift.joinToString("\n"), 0, drift.size)
    }

    @Test
    fun `remote sync claim matches git refs`() {
        // 非普通 git 检出（如 .git 为文件的 worktree）时无实况可对照，本例不适用，不写假断言。
        val local = resolveRef("refs/heads/main") ?: return
        val remote = resolveRef("refs/remotes/origin/main")
        // 两者一致时任何同步表述都属实；不一致时声称「已同步」即为漂移。
        if (remote == local) return

        val claim = syncClaimRegex()
        val targets = listOf(file("docs/STATUS.md")) + mirrors()
        val hits = targets.flatMap { target ->
            target.readLines().mapIndexedNotNull { index, line ->
                if (claim.containsMatchIn(line)) {
                    "  ${target.name}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertEquals(
            "文档声称与 origin/main 同步，但 refs/heads/main=$local ≠ refs/remotes/origin/main=$remote：\n" +
                hits.joinToString("\n"),
            0,
            hits.size,
        )
    }

    @Test
    fun `drift detectors fire on the two known bad inputs`() {
        val scripts = gradleScriptText()
        assertTrue("1.0.1 应能在构建脚本里解析到", containsVersion(scripts, "1.0.1"))
        assertTrue(
            "1.9.0 必须解析不到，否则本门禁抓不住版本锁表曾写的 documentfile 1.9.0",
            !containsVersion(scripts, "1.9.0"),
        )

        // 端到端负控：用内存里的坏样本走真实提取链路，证明检查会判红（不改动仓库文档）。
        val badRow = "| 其他 | documentfile 1.9.0（SAF）、core-ktx 1.12.0（FileProvider） |"
        val unresolved = versionLiterals(badRow).filter { !containsVersion(scripts, it) }
        assertTrue(
            "坏样本行必须被判为漂移，否则版本锁检查形同虚设；实测未解析项=$unresolved",
            unresolved.contains("1.9.0"),
        )
        assertTrue("同一行里属实的 1.12.0 不得被误报", !unresolved.contains("1.12.0"))

        val claim = syncClaimRegex()
        assertTrue(
            "同步断言的匹配式必须命中「已与 origin/main 同步」句式",
            claim.containsMatchIn("- `origin/main` 已与 `main` 同步，clone 默认拿到新栈。"),
        )
        assertTrue(
            "同步断言的匹配式必须命中「main 已与 origin/main 同步」句式",
            claim.containsMatchIn("- `main` 已与 `origin/main` 同步。"),
        )
        assertTrue(
            "描述领先/落后关系的表述不得被误判为同步断言",
            !claim.containsMatchIn("- `main` 领先 `origin/main` 1 个提交（未推送）。"),
        )
    }
}
