package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.ui.theme.LanSyncMetrics
import com.lansync.app.ui.theme.LanSyncTheme

private const val ITEM_TYPE_REMOTE = "remote_app_item"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAppListScreen(
    connectedDevices: List<DeviceInfo>,
    selectedPackages: Set<String>,
    isRefreshing: Boolean = false,
    onToggleSelected: (String) -> Unit,
    onSelectAll: (List<RemoteAppEntry>) -> Unit,
    onClearSelection: () -> Unit,
    onPullApp: (RemoteAppEntry) -> Unit,
    onPullSelected: (List<RemoteAppEntry>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(AppCategory.ALL) }
    var hasTriggeredInitialRefresh by remember { mutableStateOf(false) }

    val remoteEntries by remember(connectedDevices) {
        derivedStateOf {
            val bestByPackage = linkedMapOf<String, RemoteAppEntry>()
            for (device in connectedDevices) {
                for (app in device.appList) {
                    val existing = bestByPackage[app.packageName]
                    if (existing == null || app.versionCode > existing.app.versionCode) {
                        bestByPackage[app.packageName] = RemoteAppEntry(app = app, sourceDevice = device)
                    }
                }
            }
            bestByPackage.values.toList()
        }
    }

    val connectedDeviceCount by remember(connectedDevices) {
        derivedStateOf {
            connectedDevices.count {
                it.connectionState == ConnectionState.CONNECTED || it.connectionState == ConnectionState.RECONNECTING
            }
        }
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

    val filteredApps by remember(searchQuery, category, remoteEntries) {
        derivedStateOf {
            remoteEntries.filter { entry ->
                val matchesSearch = searchQuery.isEmpty() ||
                    entry.app.appName.contains(searchQuery, ignoreCase = true) ||
                    entry.app.packageName.contains(searchQuery, ignoreCase = true)
                val matchesCategory = when (category) {
                    AppCategory.ALL -> true
                    AppCategory.USER -> !entry.app.isSystemApp
                    AppCategory.SYSTEM -> entry.app.isSystemApp
                }
                matchesSearch && matchesCategory
            }
        }
    }

    val totalAppCount by remember(remoteEntries) { derivedStateOf { remoteEntries.size } }
    val userAppCount by remember(remoteEntries) { derivedStateOf { remoteEntries.count { !it.app.isSystemApp } } }
    val systemAppCount by remember(remoteEntries) { derivedStateOf { remoteEntries.count { it.app.isSystemApp } } }

    val isAllSelected by remember(filteredApps, selectedPackages) {
        derivedStateOf {
            filteredApps.isNotEmpty() && filteredApps.all { it.app.packageName in selectedPackages }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.search_remote_placeholder)
        )

        CategoryTabs(
            selectedCategory = category,
            onCategoryChange = { category = it },
            totalCount = totalAppCount,
            userCount = userAppCount,
            systemCount = systemAppCount
        )

        Spacer(modifier = Modifier.height(sp.space4))

        SectionHeader(
            title = stringResource(R.string.remote_section_title),
            icon = Icons.Default.Cloud,
            count = filteredApps.size,
            showSelectButtons = filteredApps.isNotEmpty(),
            areAllSelected = isAllSelected,
            onSelectAll = { onSelectAll(filteredApps) },
            onClearSelection = onClearSelection
        )

        if (connectedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(R.string.remote_no_device_title),
                    body = stringResource(R.string.remote_empty_hint),
                )
            }
        } else {
            MultiDeviceSummary(
                connectedCount = connectedDeviceCount,
                totalApps = totalAppCount,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
            )
        }

        if (connectedDevices.isNotEmpty() && remoteEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Default.SyncProblem,
                    title = stringResource(R.string.remote_empty_applist),
                    actionLabel = stringResource(R.string.action_refresh_applist),
                    onAction = onRefresh,
                )
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Default.Inbox,
                    title = when {
                        searchQuery.isNotEmpty() ->
                            stringResource(R.string.remote_empty_search, searchQuery)
                        category == AppCategory.USER ->
                            stringResource(R.string.remote_empty_user)
                        category == AppCategory.SYSTEM ->
                            stringResource(R.string.remote_empty_system)
                        else ->
                            stringResource(R.string.remote_empty_all)
                    },
                )
            }
        } else {
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

@Composable
fun MultiDeviceSummary(
    connectedCount: Int,
    totalApps: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    SummaryCard(
        primary = stringResource(R.string.summary_connected_devices, connectedCount),
        secondary = stringResource(R.string.summary_remote_apps, totalApps),
        actionLabel = if (isRefreshing) {
            stringResource(R.string.status_refreshing_short)
        } else {
            stringResource(R.string.cd_refresh)
        },
        onAction = if (isRefreshing) null else onRefresh,
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LanSyncMetrics.listItemMin),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                LanSyncTheme.containers.highest
            } else {
                LanSyncTheme.containers.low
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = sp.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = sp.space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected(entry.app.packageName) },
                modifier = Modifier.padding(start = sp.space4)
            )

            AppIcon(
                packageName = entry.app.packageName,
                size = sp.appIconLg
            )

            Spacer(modifier = Modifier.width(sp.space10))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!entry.app.isExtractable) {
                        Spacer(modifier = Modifier.width(sp.space6))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(sp.radiusXs)
                        ) {
                            Text(
                                text = stringResource(R.string.badge_not_extractable),
                                modifier = Modifier.padding(horizontal = sp.space4, vertical = sp.hairline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(sp.space2))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(sp.space8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.app.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.app.fileSize > 0) {
                        val sizeMb = remember(entry.app.fileSize) { entry.app.fileSize / (1024 * 1024) }
                        Text(
                            text = stringResource(R.string.size_mb, sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = sp.space2)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(sp.iconXs)
                    )
                    Spacer(modifier = Modifier.width(sp.space4))
                    Text(
                        text = entry.sourceDevice.deviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(sp.space8))

            Button(
                onClick = onPull,
                modifier = Modifier.height(sp.buttonHeight),
                contentPadding = PaddingValues(horizontal = sp.space10),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(sp.iconSm)
                )
                Spacer(modifier = Modifier.width(sp.space4))
                Text(stringResource(R.string.action_pull), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
