package com.lansync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncMonoText
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 共享 UI 原语。
 *
 * 颜色只经 `MaterialTheme.colorScheme` / [LanSyncTheme.containers] 的成对角色；
 * 尺寸走 `LanSyncTheme.spacing`，圆角走 `MaterialTheme.shapes`，阴影走
 * `LanSyncTheme.elevation`，动效走 `LanSyncTheme.motion`。
 */

// ---------------------------------------------------------------- 状态徽标

@Composable
fun StatusChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val sp = LanSyncTheme.spacing
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = sp.space8, vertical = sp.space2),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(sp.iconXs),
                )
                Spacer(Modifier.width(sp.space4))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 系统徽标种类：色对与圆角在此集中，不允许在行组件里手搓 `Surface + copy(alpha)`。 */
enum class LanSyncBadgeKind { NotExtractable, Split, Updatable, Online }

@Composable
fun LanSyncBadge(
    kind: LanSyncBadgeKind,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val visual: Triple<String, Color, Color> = when (kind) {
        LanSyncBadgeKind.NotExtractable ->
            Triple(stringResource(R.string.badge_not_extractable), scheme.errorContainer, scheme.onErrorContainer)
        LanSyncBadgeKind.Split ->
            Triple(stringResource(R.string.badge_split), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        LanSyncBadgeKind.Updatable ->
            Triple(stringResource(R.string.badge_updatable), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        LanSyncBadgeKind.Online ->
            Triple(stringResource(R.string.device_online_badge), scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    StatusChip(
        text = visual.first,
        containerColor = visual.second,
        contentColor = visual.third,
        leadingIcon = if (kind == LanSyncBadgeKind.Updatable) Icons.Default.NewReleases else null,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- 选择 chip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSyncFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val sp = LanSyncTheme.spacing
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = when {
            selected -> {
                {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(sp.iconMd),
                    )
                }
            }
            leadingIcon != null -> {
                { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(sp.chipIcon)) }
            }
            else -> null
        },
        modifier = modifier.heightIn(min = sp.minTouchTarget),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

// ---------------------------------------------------------------- 空态 / 信息卡

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sp.iconHero)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(LanSyncTheme.containers.default),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(sp.iconEmptyLg),
            )
        }
        Spacer(Modifier.height(sp.space16))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(sp.space8))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(sp.space16))
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = sp.minTouchTarget),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun MeshHint(
    onlineCount: Int,
    totalVisible: Int,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    val online = onlineCount.coerceAtMost(3)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LanSyncTheme.containers.low,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(sp.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(sp.space10)
                        .clip(CircleShape)
                        .background(
                            if (index < online) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
                if (index < 2) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = sp.space2)
                            .width(sp.space8)
                            .height(sp.strokeThin)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Spacer(Modifier.width(sp.space10))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mesh_hint_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.mesh_hint_stats, onlineCount, totalVisible),
                    style = LanSyncMonoText.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 无在线设备时不贴「在线」，避免与 0/N 统计自相矛盾
            if (onlineCount > 0) {
                LanSyncBadge(LanSyncBadgeKind.Online)
            }
        }
    }
}

@Composable
fun SummaryCard(
    primary: String,
    secondary: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LanSyncTheme.containers.low,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(sp.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (secondary != null) {
                    Spacer(Modifier.height(sp.space4))
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // busy 时保留按钮槽位（换成指示器），避免文字跳动且「进行中」状态始终可见
            if (busy) {
                LanSyncSpinner(size = sp.iconXl, strokeWidth = sp.strokeThin)
            } else if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 批量操作条

/**
 * 批量操作条：只有「计数标签 + 主操作」。全选/取消全选开关在顶栏（远程/同步/文件三页统一），
 * 不再为本组件保留文字动作槽位。底色走 `secondaryContainer`/`onSecondaryContainer` 配对
 * （浅色自动淡、深色自动深），不用 `inverseSurface`：反转底与 primary 文字在两种模式下都过不了辨识度。
 */
@Composable
fun BatchBar(
    label: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.padding(horizontal = sp.space16, vertical = sp.space8),
    ) {
        Row(
            modifier = Modifier.padding(sp.space12),
            horizontalArrangement = Arrangement.spacedBy(sp.space8, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = sp.minTouchTarget),
            ) {
                Text(actionLabel)
            }
        }
    }
}

// ---------------------------------------------------------------- 分区头 / 搜索 / 强调条

@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = sp.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(sp.iconLg),
            )
            Spacer(Modifier.width(sp.space6))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(sp.space6))
            val countDescription = stringResource(R.string.section_count_description, title, count)
            StatusChip(
                text = count.toString(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.semantics { contentDescription = countDescription },
            )
        }
    }
}

/** 更新项左侧 tertiary 竖条。 */
@Composable
fun UpdateAccentBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(LanSyncTheme.spacing.accentBar)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.tertiary),
    )
}

// ---------------------------------------------------------------- 行内动作按钮

/**
 * 行内主/次操作按钮：触控高度走 `minTouchTarget`（48dp 下限），
 * 不允许再用固定矮高度压掉触摸目标（CONVENTIONS §3）。
 */
@Composable
fun RowActionButton(
    onClick: () -> Unit,
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val sp = LanSyncTheme.spacing
    val content: @Composable () -> Unit = {
        if (busy) {
            LanSyncSpinner(size = sp.iconSm, strokeWidth = sp.strokeThin)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(sp.iconSm))
        }
        Spacer(Modifier.width(sp.space4))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = sp.minTouchTarget),
            contentPadding = PaddingValues(horizontal = sp.space10),
        ) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = sp.minTouchTarget),
            contentPadding = PaddingValues(horizontal = sp.space10),
        ) { content() }
    }
}

/** 下载图标（行按钮默认图标集中一处，避免各屏各取）。 */
val RowDownloadIcon: ImageVector = Icons.Default.Download

/**
 * 进行中的按钮：宽度与位置保持稳定， spinner 换 label 不跳版。
 *
 * 取代此前两处 `OutlinedButton(onClick = {}, enabled = false)` 的空 lambda 写法
 * （禁用态不需要可点击语义，但 API 要求 onClick，这里给它一个真正的 no-op 语义体）。
 */
@Composable
fun InProgressButton(
    label: String,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    OutlinedButton(
        onClick = { /* 进行中：禁用态不触发动作 */ },
        enabled = false,
        modifier = modifier.heightIn(min = sp.minTouchTarget),
        contentPadding = PaddingValues(horizontal = sp.space12),
    ) {
        LanSyncSpinner(size = sp.iconSm, strokeWidth = sp.strokeThin)
        Spacer(Modifier.width(sp.space4))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
