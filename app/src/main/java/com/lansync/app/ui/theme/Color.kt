package com.lansync.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * LanSync 品牌色板（设计系统唯一色值来源）。
 *
 * 全部 UI 颜色**只允许**通过 [androidx.compose.material3.MaterialTheme.colorScheme] 访问，
 * 组件内**禁止**出现 `Color(0x..)`/`Color.Green` 等硬编码字面量。本文件与 [Theme.kt] 的
 * ColorScheme 定义是色值的唯一落点（Phase 5「单一 Material3 设计系统」硬性要求）。
 *
 * 色系：Primary = 靛蓝（对齐启动器品牌色 #3F51B5），Secondary = 青绿（同步/网络语义），
 * Tertiary = 琥珀（更新/差异强调），Error = M3 标准红。命名后缀为 M3 色调号（10 最深 → 95 最浅）。
 */

// ---- Primary · 靛蓝 ----
internal val Indigo10 = Color(0xFF00105C)
internal val Indigo20 = Color(0xFF10235C)
internal val Indigo30 = Color(0xFF2F3E9E)
internal val Indigo40 = Color(0xFF3F51B5)
internal val Indigo80 = Color(0xFFBAC3FF)
internal val Indigo90 = Color(0xFFDEE0FF)
internal val Indigo95 = Color(0xFFEFF0FF)

// ---- Secondary · 青绿 ----
internal val Teal10 = Color(0xFF00201C)
internal val Teal20 = Color(0xFF003731)
internal val Teal30 = Color(0xFF005147)
internal val Teal40 = Color(0xFF00695C)
internal val Teal80 = Color(0xFF7FCEC3)
internal val Teal90 = Color(0xFFA4F2E7)
internal val Teal95 = Color(0xFFE0F7F3)

// ---- Tertiary · 琥珀 ----
internal val Amber10 = Color(0xFF2E1600)
internal val Amber20 = Color(0xFF4A2800)
internal val Amber30 = Color(0xFF6C3D00)
internal val Amber40 = Color(0xFF8B5000)
internal val Amber80 = Color(0xFFF8BC63)
internal val Amber90 = Color(0xFFFFDDB5)
internal val Amber95 = Color(0xFFFFF1DE)

// ---- Error · 红 ----
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// ---- Neutral · 靛蓝微染中性色 ----
internal val Neutral10 = Color(0xFF14141A)
internal val Neutral20 = Color(0xFF2A2A33)
internal val Neutral30 = Color(0xFF45464F)
internal val Neutral40 = Color(0xFF5C5D67)
internal val Neutral80 = Color(0xFFC6C5D0)
internal val Neutral90 = Color(0xFFE2E1EC)
internal val Neutral95 = Color(0xFFF1EFF7)
internal val Neutral99 = Color(0xFFFBF8FD)

internal val NeutralVariant30 = Color(0xFF45464F)
internal val NeutralVariant50 = Color(0xFF767680)
internal val NeutralVariant60 = Color(0xFF90909A)
internal val NeutralVariant80 = Color(0xFFC6C5D0)

// ---- 语义常量 ----
internal val PureWhite = Color(0xFFFFFFFF)
internal val PureBlack = Color(0xFF000000)
internal val ScrimColor = Color(0xFF000000)
