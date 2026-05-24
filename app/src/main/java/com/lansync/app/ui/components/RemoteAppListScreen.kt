package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.data.model.UpdateInfo

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
    var searchQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AppCategory.ALL) }
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

    val isAllSelected = filteredApps.isNotEmpty() &&
        filteredApps.all { it.app.packageName in selectedPackages }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "搜索远程应用..."
        )

        CategoryTabs(
            selectedCategory = category,
            onCategoryChange = { category = it },
            totalCount = remoteEntries.size,
            userCount = remoteEntries.count { !it.app.isSystemApp },
            systemCount = remoteEntries.count { it.app.isSystemApp }
        )

        Spacer(modifier = Modifier.height(4.dp))

        SectionHeader(
            title = "远程应用",
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
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "暂无连接的远程设备",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请在「设备」页面发现并连接设备后使用此功能",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            MultiDeviceSummary(
                connectedCount = connectedDeviceCount,
                totalApps = remoteEntries.size,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh
            )
        }

        if (connectedDevices.isNotEmpty() && remoteEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SyncProblem,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "应用列表为空",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新应用列表")
                    }
                }
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            searchQuery.isNotEmpty() -> "未找到匹配「$searchQuery」的远程应用"
                            category == AppCategory.USER -> "暂无远程用户应用"
                            category == AppCategory.SYSTEM -> "暂无远程系统应用"
                            else -> "暂无远程应用数据"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { "remote_${it.app.packageName}" }) { entry ->
                    RemoteAppItem(
                        entry = entry,
                        isSelected = entry.app.packageName in selectedPackages,
                        onToggleSelected = onToggleSelected,
                        onPull = { onPullApp(entry) }
                    )
                }
            }

            if (selectedPackages.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选择 ${selectedPackages.size} 个应用",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = { onPullSelected(filteredApps) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("拉取安装", fontWeight = FontWeight.Bold)
                        }
                    }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "已连接 $connectedCount 台设备",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "共 $totalApps 个远程应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isRefreshing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "刷新中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
fun RemoteAppItem(
    entry: RemoteAppEntry,
    isSelected: Boolean,
    onToggleSelected: (String) -> Unit,
    onPull: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected(entry.app.packageName) },
                modifier = Modifier.padding(start = 4.dp)
            )

            AppIcon(
                packageName = entry.app.packageName,
                size = 44
            )

            Spacer(modifier = Modifier.width(10.dp))

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
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "不可提取",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        val sizeMb = entry.app.fileSize / (1024 * 1024)
                        Text(
                            text = "${sizeMb}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = entry.sourceDevice.deviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onPull,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("拉取", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}