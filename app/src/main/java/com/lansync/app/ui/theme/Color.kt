package com.lansync.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * LanSync 品牌色板（设计系统唯一色值来源）。
 *
 * 全部 UI 颜色**只允许**通过 [androidx.compose.material3.MaterialTheme.colorScheme] 或
 * [LanSyncTheme.containers] 访问，组件内**禁止**出现 `Color(0x..)` 等硬编码字面量。
 *
 * 色系：Primary = 信任青绿 Teal，Secondary = 中性青灰，Tertiary = 琥珀（可更新语义），
 * Error = M3 标准红。命名后缀为 M3 色调号（10 最深 → 95 最浅）。
 */

// ---- Primary · 信任青绿 ----
internal val TealPrimary10 = Color(0xFF00201E)
internal val TealPrimary20 = Color(0xFF003734)
internal val TealPrimary30 = Color(0xFF00504D)
internal val TealPrimary40 = Color(0xFF00696B)
internal val TealPrimary80 = Color(0xFF76D0CC)
internal val TealPrimary90 = Color(0xFF97F0EB)
internal val TealPrimary95 = Color(0xFFC8F8F5)

// ---- Secondary · 中性青灰 ----
internal val TealSecondary10 = Color(0xFF051F1B)
internal val TealSecondary20 = Color(0xFF193530)
internal val TealSecondary30 = Color(0xFF324B47)
internal val TealSecondary40 = Color(0xFF4A635F)
internal val TealSecondary80 = Color(0xFFB0CCC7)
internal val TealSecondary90 = Color(0xFFCCE8E2)

// ---- Tertiary · 琥珀（可更新）----
internal val Amber10 = Color(0xFF231B00)
internal val Amber20 = Color(0xFF3A2F00)
internal val Amber30 = Color(0xFF52461B)
internal val Amber40 = Color(0xFF6B5E31)
internal val Amber80 = Color(0xFFDAC488)
internal val Amber90 = Color(0xFFF4E1A6)

// ---- Error ----
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// ---- Neutral（青绿微染）----
internal val Neutral10 = Color(0xFF181D1D)
internal val Neutral20 = Color(0xFF2C3232)
internal val Neutral40 = Color(0xFF5C6664)
internal val Neutral90 = Color(0xFFE0E3E2)
internal val Neutral95 = Color(0xFFEEF2F1)
internal val Neutral99 = Color(0xFFF6FAF9)

// Light surface 容器
internal val SurfaceLowLight = Color(0xFFF0F4F3)
internal val SurfaceContainerLight = Color(0xFFEAEEED)
internal val SurfaceContainerHighLight = Color(0xFFE4E9E8)
internal val SurfaceContainerHighestLight = Color(0xFFDEE4E3)

// Dark surface 容器（非纯黑）
internal val SurfaceDark = Color(0xFF0E1416)
internal val SurfaceLowDark = Color(0xFF151C1E)
internal val SurfaceContainerDark = Color(0xFF1A2224)
internal val SurfaceContainerHighDark = Color(0xFF242D2F)
internal val SurfaceContainerHighestDark = Color(0xFF2F393B)

internal val OutlineLight = Color(0xFF6F7977)
internal val OutlineVariantLight = Color(0xFFBFC9C7)
internal val OutlineDark = Color(0xFF8A9391)
internal val OutlineVariantDark = Color(0xFF404948)

internal val OnSurfaceVariantLight = Color(0xFF3F4948)
internal val OnSurfaceVariantDark = Color(0xFFBEC9C7)

internal val PureWhite = Color(0xFFFFFFFF)
internal val ScrimColor = Color(0xFF000000)
