package com.lansync.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.ui.theme.LanSyncTheme

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
    onRefreshDevices: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    // 只保存 displayKey（String 可入 Bundle）；DeviceInfo 非 Parcelable，rememberSaveable 会在恢复时崩溃
    var selectedDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showDiffsOnly by rememberSaveable { mutableStateOf(false) }

    if (connectedDevices.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(sp.space16), contentAlignment = Alignment.Center) {
            EmptySyncState()
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).padding(sp.space16)) {
                val currentDevice = connectedDevices.firstOrNull { it.displayKey == selectedDeviceKey }
                    ?: connectedDevices.firstOrNull()

                ConnectedDeviceChips(
                    devices = connectedDevices,
                    selectedDevice = currentDevice,
                    onSelectDevice = { selectedDeviceKey = it.displayKey }
                )

                Spacer(Modifier.height(sp.space8))

                if (currentDevice != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(sp.space6))
                            Text(
                                text = stringResource(R.string.sync_device_title, currentDevice.deviceName),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.search_user_placeholder)
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
                                    (searchQuery.isEmpty() ||
                                        diff.appInfo.appName.contains(searchQuery, ignoreCase = true))
                            }
                        }
                    }

                    SyncModeRow(
                        updatableCount = deviceUpdates.size,
                        diffCount = deviceDiffs.count { it.diffType == SyncDiff.DiffType.NEWER_ON_REMOTE },
                        showDiffsOnly = showDiffsOnly,
                        onModeChange = { showDiffsOnly = it },
                    )

                    if (!showDiffsOnly) {
                        SectionHeader(
                            title = stringResource(R.string.sync_section_user),
                            icon = Icons.Default.Person,
                            count = currentDevice.appList.count { !it.isSystemApp }
                        )
                    }

                    val newerOnRemoteDiffs = remember(deviceDiffs) {
                        deviceDiffs.filter { it.diffType == SyncDiff.DiffType.NEWER_ON_REMOTE }
                    }

                    if (deviceUpdates.isEmpty() && newerOnRemoteDiffs.isEmpty() && currentDevice.appList.isNotEmpty() && searchQuery.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(sp.space24),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(sp.iconEmpty)
                                )
                                Spacer(Modifier.height(sp.space8))
                                Text(
                                    text = stringResource(R.string.sync_all_synced),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(sp.space8)
                        ) {
                            if (!showDiffsOnly && deviceUpdates.isNotEmpty()) {
                                item(key = "header_updates") {
                                    SectionHeader(
                                        title = stringResource(R.string.sync_section_updatable),
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

                            if (showDiffsOnly && newerOnRemoteDiffs.isNotEmpty()) {
                                item(key = "header_diffs") {
                                    SectionHeader(
                                        title = stringResource(R.string.sync_section_newer_on_remote),
                                        icon = Icons.Default.CompareArrows,
                                        count = newerOnRemoteDiffs.size
                                    )
                                }
                                items(newerOnRemoteDiffs, key = { "diff_${it.appInfo.packageName}" }) { diff ->
                                    DiffItem(diff = diff)
                                }
                            }

                            if (showDiffsOnly && deviceDiffs.any { it.diffType == SyncDiff.DiffType.ONLY_ON_REMOTE }) {
                                val onlyRemote = deviceDiffs.filter { it.diffType == SyncDiff.DiffType.ONLY_ON_REMOTE }
                                item(key = "header_only_remote") {
                                    SectionHeader(
                                        title = stringResource(R.string.sync_section_only_remote),
                                        icon = Icons.Default.CloudDownload,
                                        count = onlyRemote.size
                                    )
                                }
                                items(onlyRemote, key = { "only_remote_${it.appInfo.packageName}" }) { diff ->
                                    DiffItem(diff = diff)
                                }
                            }

                            if ((!showDiffsOnly && deviceUpdates.isEmpty()) ||
                                (showDiffsOnly && newerOnRemoteDiffs.isEmpty() && deviceDiffs.none { it.diffType == SyncDiff.DiffType.ONLY_ON_REMOTE })
                            ) {
                                item(key = "empty") {
                                    EmptyStateCard(
                                        message = if (searchQuery.isNotEmpty())
                                            stringResource(R.string.sync_empty_search, searchQuery)
                                        else
                                            stringResource(R.string.sync_empty_user)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedUpdates.isNotEmpty()) {
                BatchBar(
                    label = stringResource(R.string.selection_count_apps, selectedUpdates.size),
                    actionLabel = stringResource(R.string.action_batch_update),
                    onAction = onInstallSelectedUpdates,
                )
            }
        }
    }
}

@Composable
fun EmptySyncState() {
    EmptyState(
        icon = Icons.Default.LinkOff,
        title = stringResource(R.string.remote_empty_title),
        body = stringResource(R.string.remote_empty_hint),
    )
}

@Composable
private fun SyncModeRow(
    updatableCount: Int,
    diffCount: Int,
    showDiffsOnly: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = sp.space8),
        horizontalArrangement = Arrangement.spacedBy(sp.space8),
    ) {
        LanSyncFilterChip(
            selected = !showDiffsOnly,
            onClick = { onModeChange(false) },
            label = stringResource(R.string.sync_mode_updatable, updatableCount),
            modifier = Modifier.weight(1f),
        )
        LanSyncFilterChip(
            selected = showDiffsOnly,
            onClick = { onModeChange(true) },
            label = stringResource(R.string.sync_mode_diffs, diffCount),
            modifier = Modifier.weight(1f),
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
    val sp = LanSyncTheme.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(sp.space6),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        devices.forEach { device ->
            FilterChip(
                selected = selectedDevice?.displayKey == device.displayKey,
                onClick = { onSelectDevice(device) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(sp.chipIcon))
                        Spacer(Modifier.width(sp.space4))
                        Text(device.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                leadingIcon = if (selectedDevice?.displayKey == device.displayKey) {
                    { Icon(Icons.Default.Check, null, Modifier.size(sp.iconMd)) }
                } else null
            )
        }
    }
}

@Composable
fun DiffItem(diff: SyncDiff) {
    val sp = LanSyncTheme.spacing
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = sp.hairline)
    ) {
        Row(
            modifier = Modifier.padding(sp.space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = diff.appInfo.packageName, size = sp.appIconMd)

            Spacer(Modifier.width(sp.space10))

            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    diff.appInfo.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(Modifier.padding(top = sp.space2)) {
                    Text(
                        diff.localVersion ?: stringResource(R.string.diff_version_placeholder),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.version_arrow),
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
                shape = RoundedCornerShape(sp.radiusSm),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f)
            ) {
                Text(
                    stringResource(R.string.diff_newer_on_remote),
                    modifier = Modifier.padding(horizontal = sp.space8, vertical = sp.space4),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}
