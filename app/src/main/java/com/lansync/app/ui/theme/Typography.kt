package com.lansync.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * LanSync 统一字体排印（Material3 类型比例）。
 *
 * 设计系统唯一字体来源；组件**禁止**内联 `fontSize`/`letterSpacing`/`fontWeight` 字面量，
 * 一律经 [androidx.compose.material3.MaterialTheme.typography] 引用下列样式，
 * 需要更强存在感时改用更高一级的样式（如 `titleMedium` 代替 `titleSmall + Bold`），
 * 保证全局一致。未显式定义的样式沿用 Material3 默认值。
 */
private val LanSyncTypeScale = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

val Typography: Typography = LanSyncTypeScale

/**
 * 等宽技术数值样式（IP:端口、端口号、在线统计）。
 *
 * 「技术数值用等宽」是设计系统约定，此前散落在 3 个组件里各写一次
 * `fontFamily = FontFamily.Monospace`；现在只从这里取，字号仍沿用类型比例。
 */
object LanSyncMonoText {
    val labelSmall: TextStyle get() = LanSyncTypeScale.labelSmall.withMono()
    val bodySmall: TextStyle get() = LanSyncTypeScale.bodySmall.withMono()

    private fun TextStyle.withMono(): TextStyle = copy(
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.sp,
    )
}
