package com.lansync.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计系统门禁（CONVENTIONS §3「零硬编码三禁」的自动化形态 + 令牌表健康度）。
 *
 * 与 `LayeringTest` / `DocFactsTest` 同形态：零新依赖，靠源码文本扫描，直接跑在
 * `testDebugUnitTest` 里。此前这条审计只靠人工 grep，`docs/ROADMAP.md` §4 登记过
 * 「机械断言会因 @Preview 大量误报」—— 现在 `@Preview` 内也只用令牌与资源，
 * 所以门禁不需要任何豁免规则。
 *
 * 没有豁免文件：领域层失败原因已改成 typed `ConnectionFailure` / `InstallFailure`，
 * UI 侧只做「类别 → 资源」映射（`ui/string/FailureText.kt`），不再需要按中文字面量匹配。
 *
 * ## 被禁写法只有一个定义处
 *
 * 每条规则的正则都由 [ban] 工厂注册进 [banned]，规则与自检拿到的是同一个对象；
 * `violationsOf` 只接受 [Banned]，不接受裸 `Regex`。这样两条静默失效的路被堵住：
 *
 * - 自检里另抄一份正则 → 抄的那份写对了、规则那份写错了，门禁照样全绿。
 * - 新增规则忘了登记坏样本 → 没有负控，规则哪天失效无人知道。
 *
 * 本文件里正则一律写成三引号收尾（`"""…""> ` 的第四引号会把 `"` 吃进内容，
 * 让模式末尾多出一个永远匹配不上的字符），且模式不再有任何第二份副本。
 */
class DesignSystemTest {

    /**
     * 一条被禁写法。[name] 必须与它注册的字段名一致（[every_registered_pattern_is_enforced] 按此反查）。
     */
    private data class Banned(val name: String, val what: String, val pattern: Regex, val sample: String)

    private val banned = mutableListOf<Banned>()

    /** 注册即绑定「规则名 + 正则 + 最小坏样本」，返回的对象就是规则要用的那个。 */
    private fun ban(name: String, what: String, regex: String, sample: String): Banned {
        val entry = Banned(name, what, Regex(regex), sample)
        banned += entry
        return entry
    }

    private val colorLiteral = ban(
        "colorLiteral", "色字面量",
        """Color\(\s*0x""",
        "val tint = Color(0xFF00696B)",
    )
    private val bareDp = ban(
        "bareDp", "裸 .dp 字面量",
        """[^a-zA-Z]\d+(\.\d+)?\.dp\b""",
        "Modifier.width(16.dp)",
    )
    private val inlineTypographyProp = ban(
        "inlineTypographyProp", "内联 fontSize/letterSpacing",
        """\b(fontSize|letterSpacing)\s*=""",
        "TextStyle(fontSize = size)",
    )
    private val bareSp = ban(
        "bareSp", "裸 .sp 字面量",
        """[^a-zA-Z]\d+(\.\d+)?\.sp\b""",
        "fontSize = 14.sp",
    )
    private val inlineFontWeight = ban(
        "inlineFontWeight", "内联字重覆盖",
        """fontWeight\s*=\s*FontWeight\.""",
        "fontWeight = FontWeight.Bold",
    )
    private val alphaTint = ban(
        "alphaTint", "自造 alpha 淡化",
        """\.copy\(\s*alpha""",
        "statusColor.copy(alpha = 0.15f)",
    )
    private val motionLiteral = ban(
        "motionLiteral", "写死的动效时长/延时",
        """(tween|delay)\(\s*\d+""",
        "animationSpec = tween(300)",
    )
    private val hanLiteral = ban(
        "hanLiteral", "内联中文串",
        "\"[^\"]*[\\u4E00-\\u9FFF][^\"]*\"",
        "text = \"正在加载\"",
    )
    private val rawProgressIndicator = ban(
        "rawProgressIndicator", "裸进度指示器",
        """(Circular|Linear)ProgressIndicator\(""",
        "CircularProgressIndicator(modifier = m)",
    )
    private val rawFilterChip = ban(
        "rawFilterChip", "裸 FilterChip",
        """(?<![A-Za-z])FilterChip\(""",
        "FilterChip(selected = s, onClick = o)",
    )
    private val surfaceVariantRole = ban(
        "surfaceVariantRole", "surfaceVariant 容器色",
        """\bsurfaceVariant\b""",
        "val bg = scheme.surfaceVariant",
    )
    private val fixedButtonHeight = ban(
        "fixedButtonHeight", "交互控件固定高度",
        """Modifier\.height\([^)]*(button|Button)""",
        "Modifier.height(confirmButtonHeight)",
    )

    private val uiRoot: File = listOf(
        "src/main/java/com/lansync/app/ui",
        "app/src/main/java/com/lansync/app/ui",
    ).map(::File).firstOrNull { it.isDirectory }
        ?: error("找不到 ui 源码目录，工作目录=${System.getProperty("user.dir")}")

    private val mainActivity: File = listOf(
        "src/main/java/com/lansync/app/MainActivity.kt",
        "app/src/main/java/com/lansync/app/MainActivity.kt",
    ).map(::File).firstOrNull { it.isFile }
        ?: error("找不到 MainActivity.kt")

    private val allUiSources: List<File> =
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() + mainActivity

    /** 审计范围：ui/ 下非 theme 文件 + MainActivity。 */
    private val auditedFiles: List<File> =
        allUiSources.filter { !it.path.contains("${File.separator}theme${File.separator}") }

    /**
     * 只取代码行：行注释、块注释与 KDoc 的正文行会引用被禁写法本身作为说明文字，
     * 不参与匹配，否则门禁会把「不要这样写」的文档误判成违规。
     */
    private fun codeOf(line: String): String? {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("//") || text.startsWith("*") ||
            text.startsWith("/*") || text.startsWith("*/")
        ) {
            return null
        }
        return text.substringBefore("//")
    }

    private fun violationsOf(entry: Banned, files: List<File> = auditedFiles): List<String> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val text = codeOf(line) ?: return@mapIndexedNotNull null
                if (entry.pattern.containsMatchIn(text)) "${file.name}:${index + 1}: ${text.trim()}" else null
            }
        }

    /** 只扫代码行的命中数，用于原语文件内部的健康度检查。 */
    private fun matchesIn(file: File, entry: Banned): Boolean =
        file.isFile && file.readLines().mapNotNull { codeOf(it) }.any { entry.pattern.containsMatchIn(it) }

    private fun assertClean(title: String, hits: List<String>) {
        assertEquals("$title：\n" + hits.joinToString("\n"), 0, hits.size)
    }

    /** 本测试文件自己的源码，用于「注册了却没被用上」这类反向检查。 */
    private fun ownSource(): String {
        val candidate = listOf(
            "src/test/java/com/lansync/app/architecture/DesignSystemTest.kt",
            "app/src/test/java/com/lansync/app/architecture/DesignSystemTest.kt",
        ).map(::File).firstOrNull { it.isFile }
            ?: error("找不到 DesignSystemTest.kt 自身，反向检查已失效")
        return candidate.readText()
    }

    @Test
    fun `audit scope covers the whole ui layer plus MainActivity`() {
        assertTrue("审计范围内源文件数为 0，检查已失效", auditedFiles.isNotEmpty())
        assertTrue("审计范围必须包含 MainActivity", auditedFiles.any { it.name == "MainActivity.kt" })
        assertTrue(
            "审计范围必须覆盖共享原语与各屏文件",
            auditedFiles.map { it.name }.toSet().containsAll(
                listOf(
                    "CommonComponents.kt",
                    "AppRow.kt",
                    "FeedbackBanner.kt",
                    "DeviceListScreen.kt",
                    "SyncScreen.kt",
                ),
            ),
        )
    }

    @Test
    fun `no hard coded color literals outside theme`() {
        assertClean(
            "ui/ 与 MainActivity 出现色字面量（应走 Color.kt + colorScheme/containers）",
            violationsOf(colorLiteral),
        )
    }

    @Test
    fun `no bare dp literals outside theme`() {
        assertClean(
            "ui/ 与 MainActivity 出现裸 .dp 字面量（应走 LanSyncTheme.spacing）",
            violationsOf(bareDp),
        )
    }

    @Test
    fun `no inline typography literals outside theme`() {
        assertClean(
            "ui/ 出现内联 fontSize/letterSpacing/sp 字面量（应走 MaterialTheme.typography）",
            violationsOf(inlineTypographyProp) + violationsOf(bareSp),
        )
    }

    @Test
    fun `no inline font weight overrides`() {
        assertClean(
            "ui/ 出现内联 fontWeight 覆盖字阶（要更强存在感就换更高一级的样式）",
            violationsOf(inlineFontWeight),
        )
    }

    @Test
    fun `no ad hoc alpha tinting`() {
        assertClean(
            "ui/ 出现 color.copy(alpha = …) 自造淡化：把 3:1 的非文本角色降格成正文色并绕过容器配对，" +
                "需要淡底请取 *Container/on*Container 或 LanSyncTheme.containers",
            violationsOf(alphaTint),
        )
    }

    @Test
    fun `no hardcoded motion durations or delays`() {
        assertClean(
            "ui/ 出现写死的动效时长/延时（应走 LanSyncTheme.motion）",
            violationsOf(motionLiteral),
        )
    }

    @Test
    fun `no user facing text literals`() {
        assertClean(
            "ui/ 与 MainActivity 出现内联中文串（应走 strings.xml；失败原因走 typed failure → FailureText）",
            violationsOf(hanLiteral),
        )
    }

    @Test
    fun `loading vocabulary stays inside the shared primitive`() {
        assertClean(
            "共享原语之外出现裸 CircularProgressIndicator / LinearProgressIndicator" +
                "（各处自选尺寸与线宽，同一个「加载中」会长得不一样；应走 LanSyncSpinner / LanSyncProgressBar）",
            violationsOf(rawProgressIndicator, auditedFiles.filter { it.name != "LoadingVisuals.kt" }),
        )
    }

    @Test
    fun `raw chip component stays inside shared primitives`() {
        assertClean(
            "共享原语之外出现裸 FilterChip（会绕开 minTouchTarget 与集中定义的选中配色）",
            violationsOf(rawFilterChip, auditedFiles.filter { it.name != "CommonComponents.kt" }),
        )
    }

    @Test
    fun `container colors come from the compat layer not surfaceVariant`() {
        assertClean(
            "ui/ 组件里不得用 surfaceVariant 作容器色（M3 1.1 缺容器角色，LanSyncTheme.containers 是唯一入口）；" +
                "按角色名匹配，`val scheme = MaterialTheme.colorScheme` 之后再写 scheme.surfaceVariant 也照样抓",
            violationsOf(surfaceVariantRole),
        )
    }

    @Test
    fun `no fixed height on interactive controls`() {
        assertClean(
            "交互控件应走 heightIn(min = spacing.minTouchTarget)，不要固定高度",
            violationsOf(fixedButtonHeight),
        )
    }

    @Test
    fun `spacing tokens all have call sites`() {
        checkTokensDefinedAndUsed("Spacing.kt")
    }

    @Test
    fun `elevation tokens all have call sites`() {
        checkTokensDefinedAndUsed("Elevation.kt")
    }

    /**
     * 令牌表健康度：`Spacing.kt` / `Elevation.kt` 里声明的每个字段都必须至少有一个调用点。
     *
     * `Motion.kt` 不在本检查内：它的时长字段由同文件内的 tween 构造器消费（减弱动效时退化），
     * 属于内部管道；对外只暴露 `standard()/exit()/refreshSpin()` 与 `*Tween()`。
     */
    private fun checkTokensDefinedAndUsed(tokenFileName: String) {
        val tokenFile = File(uiRoot, "theme/$tokenFileName")
        assertTrue("找不到令牌文件：${tokenFile.path}", tokenFile.isFile)

        val declared = tokenFile.readLines()
            .mapNotNull { Regex("""^\s+val ([a-z][A-Za-z0-9]*)\s*:""").find(it)?.groupValues?.get(1) }
        assertTrue("$tokenFileName 未解析到任何令牌字段，检查已失效", declared.isNotEmpty())

        val others = allUiSources.filter { it.name != tokenFileName } + mainActivity
        val unused = declared.filter { name ->
            val reference = Regex("""[. ]${Regex.escape(name)}\b""")
            others.none { file -> reference.containsMatchIn(file.readText()) }
        }
        assertEquals(
            "$tokenFileName 存在零引用令牌（死值会诱导出「翻别人怎么写的」式漂移，口径见 docs/ROADMAP.md §6）：\n" +
                unused.joinToString("\n") { "  $it" },
            0,
            unused.size,
        )
    }

    @Test
    fun `typography scale entries are all used`() {
        val declared = File(uiRoot, "theme/Typography.kt").readLines()
            .mapNotNull { Regex("""^\s+([a-zA-Z]+) = TextStyle\(""").find(it)?.groupValues?.get(1) }
        assertTrue("Typography.kt 未解析到样式，检查已失效", declared.isNotEmpty())

        val unused = declared.filter { name ->
            auditedFiles.none { file -> file.readText().contains("typography.$name") }
        }
        assertEquals(
            "类型比例里存在从未使用的样式：\n" + unused.joinToString("\n") { "  $it" },
            0,
            unused.size,
        )
    }

    /**
     * 负控（CONVENTIONS §4：门禁要能证明自己会红）。
     *
     * 其余用例都是"正控"——代码干净时它们只会绿灯，正则写错、写法换个入口
     * （例如 `val scheme = MaterialTheme.colorScheme` 之后用 `scheme.surfaceVariant`）都会静默空过。
     * 这里让每条注册项拿同一个 `Regex` 对象去匹配自己登记的最小坏样本。
     */
    @Test
    fun `detectors fire on the known bad inputs`() {
        assertTrue("没有注册任何被禁写法，负控已失效", banned.isNotEmpty())

        val blind = banned.filterNot { it.pattern.containsMatchIn(it.sample) }
        assertEquals(
            "以下门禁正则连自己登记的坏样本都不认（规则已失效）：\n" +
                blind.joinToString("\n") { "  ${it.what}  pattern=[${it.pattern.pattern}]  样本=${it.sample}" },
            0, blind.size,
        )
    }

    /**
     * 反向检查：注册了坏样本却没有任何规则使用 = 白登记；在规则里内联新正则 = 绕过负控。
     * 两种都让本用例红掉，逼新增写法走 [ban]。
     */
    @Test
    fun `every registered pattern is enforced by a rule`() {
        val source = ownSource()
        val unused = banned.filterNot { source.contains("violationsOf(${it.name}") }
        assertEquals(
            "以下被禁写法注册了却没有规则真正调用它（门禁不存在）：\n" +
                unused.joinToString("\n") { "  ${it.what}" },
            0, unused.size,
        )

        // 检查用字符串运行时拼接，避免匹配到本行自身。
        val inline = "violationsOf(Regex" + "("
        assertTrue(
            "规则必须引用 ban(...) 注册项，不要内联 Regex（内联的那份没有负控）",
            !source.contains(inline),
        )
    }

    /**
     * 两条豁免（LoadingVisuals / CommonComponents）必须指向真实裸用点：
     * 原语里不再裸用该组件时，豁免就是在给一段死代码让路，应当删掉豁免。
     */
    @Test
    fun `primitive exemptions point at real call sites`() {
        val loading = File(uiRoot, "components/LoadingVisuals.kt")
        val chips = File(uiRoot, "components/CommonComponents.kt")
        assertTrue(
            "${rawProgressIndicator.what} 的豁免文件里找不到裸用点，应删除豁免：${loading.path}",
            matchesIn(loading, rawProgressIndicator),
        )
        assertTrue(
            "${rawFilterChip.what} 的豁免文件里找不到裸用点，应删除豁免：${chips.path}",
            matchesIn(chips, rawFilterChip),
        )
    }

    /**
     * 反向自检：注释行不参与匹配，否则文档里"不要这样写"的说明文字会被自己的门禁判成违规。
     */
    @Test
    fun `comment lines are excluded from matching`() {
        assertTrue("行注释必须被剔掉", codeOf("// Color(0xFF00696B)") == null)
        assertTrue("KDoc 续行必须被剔掉", codeOf(" * Color(0xFF00696B)") == null)
        assertTrue("块注释起始行必须被剔掉", codeOf("/* Color(0xFF00696B) */") == null)
        val trailing = codeOf("val c = Color(0xFF00696B) // 登记过的例外")
        assertTrue(
            "行尾注释只应保留代码部分：$trailing",
            trailing != null && trailing.startsWith("val c =") && !trailing.contains("登记过的例外"),
        )
    }
}
