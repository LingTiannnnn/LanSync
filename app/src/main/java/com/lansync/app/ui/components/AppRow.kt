package com.lansync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 列表行唯一模板（远程 / 可更新 / 已下载文件共用）。
 *
 * 结构固定：可选 tertiary 强调条 → 可选勾选框 → [icon] → 标题 + 徽标 → 说明区 → 尾部操作。
 * `RemoteAppItem` / `UpdateItem` / `DownloadedFileItem` 此前各写一份卡片、徽标、说明行与按钮，
 * 选中态与容器色因此漂移；现在只从这里取。
 *
 * 交互：
 * - 传 [checked] + [onCheckedChange] → 整行可选中，勾选框为纯视觉，语义合并在行上。
 * - 只传 [onClick] → 整行可点。
 * - 都不传 → 静态行。
 */
@Composable
fun LanSyncAppRow(
    title: String,
    packageName: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    icon: @Composable () -> Unit = { AppIcon(packageName = packageName, size = LanSyncTheme.spacing.appIconLg) },
    badges: @Composable RowScope.() -> Unit = {},
    supporting: @Composable ColumnScope.() -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    val sp = LanSyncTheme.spacing
    // 勾选行的无障碍名称要说明「这是选择某应用」，只念应用名会让 TalkBack 用户听不出这是选择还是打开
    val selectLabel = stringResource(R.string.cd_select_app, title)

    var rowModifier: Modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = sp.listItemMin)
    val onRowToggle: (() -> Unit)? = onCheckedChange?.let { change ->
        { change(checked != true) }
    }
    if (onRowToggle != null) {
        rowModifier = rowModifier
            .clickable(onClick = onRowToggle)
            .semantics {
                contentDescription = selectLabel
                role = Role.Checkbox
            }
    } else if (onClick != null) {
        rowModifier = rowModifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LanSyncTheme.containers.highest else LanSyncTheme.containers.low,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // 行卡不贴阴影：滚动期阴影 graphicsLayer 在触摸命中测试路径上有实测成本，
        // 空间感由 1dp 描边承担，选中态靠 highest 底色区分
        elevation = CardDefaults.cardElevation(defaultElevation = LanSyncTheme.elevation.flat),
        border = BorderStroke(sp.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = rowModifier.padding(end = sp.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accent) UpdateAccentBar()

            val checkboxInteractionSource = remember { MutableInteractionSource() }
            if (onCheckedChange != null) {
                Checkbox(
                    checked = checked == true,
                    onCheckedChange = null,
                    interactionSource = checkboxInteractionSource,
                    modifier = Modifier
                        .padding(start = sp.space8)
                        .semantics(mergeDescendants = true) { contentDescription = title },
                )
            }

            Spacer(modifier = Modifier.width(sp.space8))
            icon()

            Column(
                modifier = Modifier
                    .padding(start = sp.space10)
                    .weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.space6),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badges()
                }
                supporting()
            }

            if (trailing != null) {
                Spacer(modifier = Modifier.width(sp.space8))
                trailing()
            }
        }
    }
}

/**
 * 行内说明行（版本、大小、来源设备）统一样式与间距。
 *
 * 次要文本一律 `onSurfaceVariant`；此前用 `colorScheme.outline` 作 11–12sp 正文色，
 * 亮色主题下对比度只有 3.8:1，低于 AA 的 4.5:1。
 */
@Composable
fun RowSupportingText(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leadingTint: androidx.compose.ui.graphics.Color? = null,
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = modifier.padding(top = sp.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.space4),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(sp.iconXs),
            )
        }
        Text(
            text = text,
            style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
