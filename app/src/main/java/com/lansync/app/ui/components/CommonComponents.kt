package com.lansync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncMetrics
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 共享 UI 原语（COMPONENTS.md §5–§9 / §12）。
 * 颜色只经 MaterialTheme / LanSyncTheme.containers；尺寸走 spacing / LanSyncMetrics。
 */

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
        modifier = modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

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
    ) {
        Box(
            modifier = Modifier
                .size(sp.iconHero)
                .clip(RoundedCornerShape(sp.radiusXl))
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
                modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
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
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(sp.space10)
                        .clip(CircleShape)
                        .background(
                            if (i < onlineCount.coerceAtMost(3)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        ),
                )
                if (i < 2) {
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            StatusChip(
                text = stringResource(R.string.device_online_badge),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
fun SummaryCard(
    primary: String,
    secondary: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun BatchBar(
    label: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Surface(
        shape = RoundedCornerShape(sp.radiusLg),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = sp.space6,
        modifier = modifier.padding(horizontal = sp.space16, vertical = sp.space8),
    ) {
        Row(
            modifier = Modifier.padding(
                start = sp.space16,
                end = sp.space12,
                top = sp.space12,
                bottom = sp.space12,
            ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inversePrimary,
                    contentColor = MaterialTheme.colorScheme.inverseSurface,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    count: Int,
    showSelectButtons: Boolean = false,
    areAllSelected: Boolean = false,
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = sp.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
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
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(sp.space6))
            StatusChip(
                text = count.toString(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (showSelectButtons) {
            TextButton(
                onClick = { if (areAllSelected) onClearSelection?.invoke() else onSelectAll?.invoke() },
                contentPadding = PaddingValues(horizontal = sp.space12),
                modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
            ) {
                Text(
                    stringResource(
                        if (areAllSelected) R.string.action_clear_selection else R.string.action_select_all,
                    ),
                )
            }
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sp.space16, vertical = sp.space10)
            .heightIn(min = LanSyncMetrics.searchBarHeight),
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(sp.radiusLg),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
    )
}

/** 更新项左侧 tertiary 竖条（COMPONENTS.md §5）。 */
@Composable
fun UpdateAccentBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(LanSyncMetrics.updateAccentBar)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.tertiary),
    )
}

@Preview(name = "EmptyState Light")
@Preview(name = "EmptyState Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStatePreview() {
    LanSyncTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            EmptyState(
                icon = Icons.Default.Info,
                title = "暂无数据",
                body = "连接设备后开始同步",
                actionLabel = "重试",
                onAction = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(name = "MeshHint Light")
@Preview(name = "MeshHint Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MeshHintPreview() {
    LanSyncTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MeshHint(onlineCount = 2, totalVisible = 3, modifier = Modifier.padding(16.dp))
        }
    }
}

@Preview(name = "BatchBar Light")
@Preview(name = "BatchBar Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BatchBarPreview() {
    LanSyncTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            BatchBar(label = "已选 3 · 约 120 MB", actionLabel = "一键更新", onAction = {})
        }
    }
}
