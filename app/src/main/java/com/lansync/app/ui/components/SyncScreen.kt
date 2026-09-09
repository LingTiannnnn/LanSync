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
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(connectedDevices.firstOrNull()) }
    var searchQuery by remember { mutableStateOf("") }

    if (connectedDevices.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(sp.space16), contentAlignment = Alignment.Center) {
            EmptySyncState()
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).padding(sp.space16)) {
                ConnectedDeviceChips(
                    devices = connectedDevices,
                    selectedDevice = selectedDevice,
                    onSelectDevice = { selectedDevice = it }
                )

                Spacer(Modifier.height(sp.space8))

                val currentDevice = selectedDevice ?: connectedDevices.firstOrNull()

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

                    SectionHeader(
                        title = stringResource(R.string.sync_section_user),
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
                            if (deviceUpdates.isNotEmpty()) {
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

                            if (deviceDiffs.isNotEmpty()) {
                                item(key = "header_diffs") {
                                    SectionHeader(
                                        title = stringResource(R.string.sync_section_diffs),
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = sp.space8
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = sp.space16, vertical = sp.space10),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.selection_count_apps, selectedUpdates.size),
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
                                modifier = Modifier.size(sp.iconMd)
                            )
                            Spacer(modifier = Modifier.width(sp.space6))
                            Text(stringResource(R.string.action_batch_update), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySyncState() {
    val sp = LanSyncTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LinkOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(sp.iconEmpty)
        )
        Spacer(Modifier.height(sp.space16))
        Text(
            text = stringResource(R.string.remote_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(sp.space4))
        Text(
            text = stringResource(R.string.remote_empty_hint),
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
