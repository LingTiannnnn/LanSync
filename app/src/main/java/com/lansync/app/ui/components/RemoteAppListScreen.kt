package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lansync.app.R
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.ui.format.formattedFileSize
import com.lansync.app.ui.theme.LanSyncTheme

private const val ITEM_TYPE_REMOTE = "remote_app_item"

enum class AppCategory { USER, SYSTEM }

/** 跨设备按包名去重，同一应用取 versionCode 最高的来源设备。顶栏「全选」与页面列表共用这一份合并结果。 */
fun mergeRemoteEntries(connectedDevices: List<DeviceInfo>): List<RemoteAppEntry> = buildList {
    val bestByPackage = linkedMapOf<String, RemoteAppEntry>()
    for (device in connectedDevices) {
        for (app in device.appList) {
            val existing = bestByPackage[app.packageName]
            if (existing == null || app.versionCode > existing.app.versionCode) {
                bestByPackage[app.packageName] = RemoteAppEntry(app = app, sourceDevice = device)
            }
        }
    }
    addAll(bestByPackage.values)
}

/** 分类过滤的唯一实现；页面列表与顶栏「是否全选」的口径必须一致。 */
fun List<RemoteAppEntry>.filterByCategory(category: AppCategory): List<RemoteAppEntry> = filter { entry ->
    when (category) {
        AppCategory.USER -> !entry.app.isSystemApp
        AppCategory.SYSTEM -> entry.app.isSystemApp
    }
}

@Composable
fun RemoteAppListScreen(
    connectedDevices: List<DeviceInfo>,
    remoteEntries: List<RemoteAppEntry>,
    category: AppCategory,
    onCategoryChange: (AppCategory) -> Unit,
    selectedPackages: Set<String>,
    isRefreshing: Boolean = false,
    onToggleSelected: (String) -> Unit,
    onPullApp: (RemoteAppEntry) -> Unit,
    onPullSelected: (List<RemoteAppEntry>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var hasTriggeredInitialRefresh by remember { mutableStateOf(false) }

    val connectedDeviceCount = connectedDevices.count {
        it.connectionState == ConnectionState.CONNECTED || it.connectionState == ConnectionState.RECONNECTING
    }

    LaunchedEffect(connectedDevices) {
        val hasEmptyAppList = connectedDevices.isNotEmpty() &&
            connectedDevices.any { it.connectionState == ConnectionState.CONNECTED && it.appList.isEmpty() }
        if (hasEmptyAppList && !hasTriggeredInitialRefresh) {
            hasTriggeredInitialRefresh = true
            onRefresh()
        }
        if (connectedDevices.all { it.appList.isNotEmpty() || it.connectionState != ConnectionState.CONNECTED }) {
            hasTriggeredInitialRefresh = false
        }
    }

    val filteredApps = remember(remoteEntries, category) { remoteEntries.filterByCategory(category) }

    Column(modifier = modifier.fillMaxSize()) {
        MultiDeviceSummary(
            connectedCount = connectedDeviceCount,
            totalApps = remoteEntries.size,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )

        CategoryTabs(
            selectedCategory = category,
            onCategoryChange = onCategoryChange,
            userCount = remoteEntries.count { !it.app.isSystemApp },
            systemCount = remoteEntries.count { it.app.isSystemApp }
        )

        when {
            connectedDevices.isEmpty() -> EmptyState(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.remote_no_device_title),
                body = stringResource(R.string.remote_empty_hint),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
            )

            remoteEntries.isEmpty() -> EmptyState(
                icon = Icons.Default.SyncProblem,
                title = stringResource(R.string.remote_empty_applist),
                titleStyle = MaterialTheme.typography.bodyLarge,
                actionLabel = stringResource(R.string.action_refresh_applist),
                onAction = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
            )

            filteredApps.isEmpty() -> EmptyState(
                icon = Icons.Default.Inbox,
                title = stringResource(
                    if (category == AppCategory.USER) R.string.remote_empty_user
                    else R.string.remote_empty_system
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = sp.space16, vertical = sp.space8),
                    verticalArrangement = Arrangement.spacedBy(sp.space8)
                ) {
                    items(
                        items = filteredApps,
                        key = { "remote_${it.app.packageName}" },
                        contentType = { ITEM_TYPE_REMOTE }
                    ) { entry ->
                        RemoteAppItem(
                            entry = entry,
                            isSelected = entry.app.packageName in selectedPackages,
                            onToggleSelected = onToggleSelected,
                            onPull = { onPullApp(entry) }
                        )
                    }
                }

                if (selectedPackages.isNotEmpty()) {
                    BatchBar(
                        label = stringResource(R.string.selection_count_apps, selectedPackages.size),
                        actionLabel = stringResource(R.string.action_pull_install),
                        onAction = { onPullSelected(filteredApps) },
                    )
                }
            }
        }
    }
}

@Composable
fun MultiDeviceSummary(
    connectedCount: Int,
    totalApps: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    // 刷新中保留按钮槽位并显示指示器：此前 onAction=null 会让整颗按钮消失，「刷新中」状态看不见
    SummaryCard(
        primary = stringResource(R.string.summary_connected_devices, connectedCount),
        secondary = stringResource(R.string.summary_remote_apps, totalApps),
        actionLabel = stringResource(R.string.action_refresh),
        onAction = onRefresh,
        busy = isRefreshing,
        modifier = Modifier.padding(horizontal = sp.space16, vertical = sp.space4),
    )
    Spacer(modifier = Modifier.height(sp.space4))
}

@Composable
fun RemoteAppItem(
    entry: RemoteAppEntry,
    isSelected: Boolean,
    onToggleSelected: (String) -> Unit,
    onPull: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    LanSyncAppRow(
        title = entry.app.appName,
        packageName = entry.app.packageName,
        selected = isSelected,
        checked = isSelected,
        onCheckedChange = { onToggleSelected(entry.app.packageName) },
        badges = {
            if (!entry.app.isExtractable) LanSyncBadge(LanSyncBadgeKind.NotExtractable)
            if (entry.app.isSplitApk) LanSyncBadge(LanSyncBadgeKind.Split)
        },
        supporting = {
            RowSupportingText(
                text = if (entry.app.fileSize > 0) {
                    stringResource(
                        R.string.version_size_line,
                        entry.app.versionName,
                        formattedFileSize(entry.app.fileSize),
                    )
                } else {
                    entry.app.versionName
                },
                modifier = Modifier.padding(top = sp.space2),
            )
            RowSupportingText(
                text = stringResource(R.string.sync_provider_only, entry.sourceDevice.deviceName),
            )
        },
        trailing = {
            RowActionButton(
                onClick = onPull,
                label = stringResource(R.string.action_pull),
                icon = Icons.Default.CloudDownload,
            )
        },
    )
}

/**
 * 分类切换：与同步页「可更新 / 版本差异」同一套 chip 开关 UI（双 chip 等宽 + 计数入标签）。
 * 本页 Column 无水平内边距，水平 16dp 在这里补齐。
 */
@Composable
internal fun CategoryTabs(
    selectedCategory: AppCategory,
    onCategoryChange: (AppCategory) -> Unit,
    userCount: Int,
    systemCount: Int
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.space16, vertical = sp.space8),
        horizontalArrangement = Arrangement.spacedBy(sp.space8),
    ) {
        LanSyncFilterChip(
            selected = selectedCategory == AppCategory.USER,
            onClick = { onCategoryChange(AppCategory.USER) },
            label = stringResource(R.string.category_user, userCount),
            modifier = Modifier.weight(1f),
        )
        LanSyncFilterChip(
            selected = selectedCategory == AppCategory.SYSTEM,
            onClick = { onCategoryChange(AppCategory.SYSTEM) },
            label = stringResource(R.string.category_system, systemCount),
            modifier = Modifier.weight(1f),
        )
    }
}
