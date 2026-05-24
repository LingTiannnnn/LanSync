package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo

@Composable
fun SyncScreen(
    connectedDevices: List<DeviceInfo>,
    syncDiffs: List<SyncDiff>,
    availableUpdates: List<UpdateInfo>,
    selectedUpdates: Set<String>,
    onRefreshDevice: (DeviceInfo) -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onInstallSelectedUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(connectedDevices.firstOrNull()) }
    var searchQuery by remember { mutableStateOf("") }

    if (connectedDevices.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            EmptySyncState()
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                ConnectedDeviceChips(
                    devices = connectedDevices,
                    selectedDevice = selectedDevice,
                    onSelectDevice = { selectedDevice = it }
                )

                Spacer(Modifier.height(8.dp))

                val currentDevice = selectedDevice ?: connectedDevices.firstOrNull()

                if (currentDevice != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${currentDevice.deviceName} 的同步对比",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "搜索用户应用..."
                    )

                    SectionHeader(
                        title = "用户应用",
                        icon = Icons.Default.Person,
                        count = currentDevice.appList.count { !it.isSystemApp }
                    )

                    val deviceUpdates by remember(searchQuery, currentDevice, availableUpdates) {
                        derivedStateOf {
                            availableUpdates.filter { update ->
                                update.providerDevice.displayKey == currentDevice.displayKey &&
                                    (searchQuery.isEmpty() ||
                                        update.remoteApp.appName.contains(searchQuery, ignoreCase = true))
                            }
                        }
                    }

                    val deviceDiffs by remember(searchQuery, currentDevice, syncDiffs) {
                        derivedStateOf {
                            syncDiffs.filter { diff ->
                                diff.sourceDevice.displayKey == currentDevice.displayKey &&
                                    diff.diffType == SyncDiff.DiffType.NEWER_ON_REMOTE &&
                                    (searchQuery.isEmpty() ||
                                        diff.appInfo.appName.contains(searchQuery, ignoreCase = true))
                            }
                        }
                    }

                    if (deviceUpdates.isEmpty() && deviceDiffs.isEmpty() && currentDevice.appList.isNotEmpty() && searchQuery.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("应用版本已同步，无差异", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (deviceUpdates.isNotEmpty()) {
                                item(key = "header_updates") {
                                    SectionHeader(
                                        title = "可更新",
                                        icon = Icons.Default.NewReleases,
                                        count = deviceUpdates.size,
                                        showSelectButtons = true,
                                        areAllSelected = deviceUpdates.all { it.remoteApp.packageName in selectedUpdates },
                                        onSelectAll = onSelectAll,
                                        onClearSelection = onClearSelection
                                    )
                                }
                                items(deviceUpdates, key = { "sync_update_${it.remoteApp.packageName}" }) { update ->
                                    UpdateItem(
                                        updateInfo = update,
                                        isSelected = update.remoteApp.packageName in selectedUpdates,
                                        onToggleSelected = onToggleSelected,
                                        onInstall = { onInstallUpdate(update) }
                                    )
                                }
                            }

                            if (deviceDiffs.isNotEmpty()) {
                                item(key = "header_diffs") {
                                    SectionHeader(
                                        title = "版本差异",
                                        icon = Icons.Default.CompareArrows,
                                        count = deviceDiffs.size
                                    )
                                }
                                items(deviceDiffs, key = { "diff_${it.appInfo.packageName}" }) { diff ->
                                    DiffItem(diff = diff)
                                }
                            }

                            if (deviceUpdates.isEmpty() && deviceDiffs.isEmpty()) {
                                item(key = "empty") {
                                    EmptyStateCard(
                                        message = if (searchQuery.isNotEmpty()) "未找到匹配「$searchQuery」的用户应用"
                                        else "该设备暂无用户应用"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedUpdates.isNotEmpty()) {
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
                            text = "已选择 ${selectedUpdates.size} 个应用",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = onInstallSelectedUpdates,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键更新", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySyncState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LinkOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "尚未连接任何设备",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "请在「设备」页面发现并连接设备后使用此功能",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedDeviceChips(
    devices: List<DeviceInfo>,
    selectedDevice: DeviceInfo?,
    onSelectDevice: (DeviceInfo) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        devices.forEach { device ->
            FilterChip(
                selected = selectedDevice?.displayKey == device.displayKey,
                onClick = { onSelectDevice(device) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(device.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                leadingIcon = if (selectedDevice?.displayKey == device.displayKey) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
        }
    }
}

@Composable
fun DiffItem(diff: SyncDiff) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = diff.appInfo.packageName, size = 40)

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    diff.appInfo.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(Modifier.padding(top = 2.dp)) {
                    Text(
                        diff.localVersion ?: "-",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        " → ",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        diff.remoteVersion,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f)
            ) {
                Text(
                    "远程版本号更高",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}