package com.lansync.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状（圆角）唯一来源。组件只经 `MaterialTheme.shapes.{extraSmall|small|medium|large|extraLarge}`
 * 取圆角，不再另立 spacing 层的 radius 字段，避免同值双入口。
 *
 * 8 / 12 / 16 / 24 / 28 五档，与旧版设计稿一致。
 */
val LanSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
