package com.lansync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.data.model.SyncDiff
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.ui.format.formattedFileSize
import com.lansync.app.ui.theme.LanSyncTheme

@Composable
fun SyncScreen(
    connectedDevices: List<DeviceInfo>,
    syncDiffs: List<SyncDiff>,
    availableUpdates: List<UpdateInfo>,
    selectedUpdates: Set<String>,
    selectedDeviceKey: String?,
    onSelectDevice: (String) -> Unit,
    onRefreshDevice: (DeviceInfo) -> Unit,
    onToggleSelected: (String) -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onInstallSelectedUpdates: () -> Unit,
    onRefreshDevices: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var showDiffsOnly by rememberSaveable { mutableStateOf(false) }

    if (connectedDevices.isEmpty()) {
        EmptyState(
            icon = Icons.Default.LinkOff,
            title = stringResource(R.string.sync_empty_title),
            body = stringResource(R.string.sync_empty_hint),
            actionLabel = stringResource(R.string.action_refresh),
            onAction = onRefreshDevices,
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .padding(sp.space16),
        )
        return
    }

    val currentDevice = connectedDevices.firstOrNull { it.displayKey == selectedDeviceKey }
        ?: connectedDevices.firstOrNull() ?: return

    val deviceUpdates = remember(currentDevice, availableUpdates) {
        availableUpdates.filter { update ->
            update.providerDevice.displayKey == currentDevice.displayKey
        }
    }
    val deviceDiffs = remember(currentDevice, syncDiffs) {
        syncDiffs.filter { diff ->
            diff.sourceDevice.displayKey == currentDevice.displayKey
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 底部留白不放这里：列表溢出时会被 16dp padding 裁出一条常驻空带，改放 LazyColumn contentPadding
        Column(
            modifier = Modifier.weight(1f)
                .padding(start = sp.space16, top = sp.space16, end = sp.space16)
        ) {
            ConnectedDeviceChips(
                devices = connectedDevices,
                selectedDevice = currentDevice,
                onSelectDevice = { onSelectDevice(it.displayKey) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(sp.iconLg),
                )
                Spacer(Modifier.width(sp.space6))
                Text(
                    text = stringResource(R.string.sync_device_title, currentDevice.deviceName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 单台设备刷新：此前 onRefreshDevice 一直挂着没人调
                IconButton(onClick = { onRefreshDevice(currentDevice) }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SyncModeRow(
                updatableCount = deviceUpdates.size,
                diffCount = deviceDiffs.size,
                showDiffsOnly = showDiffsOnly,
                onModeChange = { showDiffsOnly = it },
            )

            if (!showDiffsOnly && deviceUpdates.isEmpty() && deviceDiffs.isEmpty() &&
                currentDevice.appList.isNotEmpty()
            ) {
                // 「已全同步」是成功态，与空态同一套原语，不再另起一张卡
                EmptyState(
                    icon = Icons.Default.CheckCircle,
                    title = stringResource(R.string.sync_all_synced),
                    modifier = Modifier.fillMaxWidth().padding(sp.space24),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(sp.space8),
                    contentPadding = PaddingValues(bottom = sp.space16),
                ) {
                    if (!showDiffsOnly && deviceUpdates.isNotEmpty()) {
                        items(deviceUpdates, key = { "sync_update_${it.remoteApp.packageName}" }) { update ->
                            UpdateItem(
                                updateInfo = update,
                                isSelected = update.remoteApp.packageName in selectedUpdates,
                                onToggleSelected = onToggleSelected,
                                onInstall = { onInstallUpdate(update) }
                            )
                        }
                    }

                    if (showDiffsOnly && deviceDiffs.isNotEmpty()) {
                        item(key = "header_diffs") {
                            SectionHeader(
                                title = stringResource(R.string.sync_section_remote_behind),
                                icon = Icons.Default.CompareArrows,
                                count = deviceDiffs.size
                            )
                        }
                        items(deviceDiffs, key = { "diff_${it.appInfo.packageName}" }) { diff ->
                            DiffItem(diff = diff)
                        }
                    }

                    if (!showDiffsOnly && deviceUpdates.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Icons.Default.Inbox,
                                title = stringResource(R.string.sync_empty_user),
                                modifier = Modifier.fillMaxWidth().padding(sp.space24),
                            )
                        }
                    }

                    if (showDiffsOnly && deviceDiffs.isEmpty()) {
                        item(key = "empty_diffs") {
                            EmptyState(
                                icon = Icons.Default.Inbox,
                                title = stringResource(R.string.sync_empty_behind),
                                modifier = Modifier.fillMaxWidth().padding(sp.space24),
                            )
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

@Composable
fun ConnectedDeviceChips(
    devices: List<DeviceInfo>,
    selectedDevice: DeviceInfo?,
    onSelectDevice: (DeviceInfo) -> Unit
) {
    val sp = LanSyncTheme.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(sp.space6),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = sp.space4),
    ) {
        devices.forEach { device ->
            // 统一走共享 chip：此前这里用裸 FilterChip，触摸高度与选中配色都绕开了设计系统
            LanSyncFilterChip(
                selected = selectedDevice?.displayKey == device.displayKey,
                onClick = { onSelectDevice(device) },
                label = device.deviceName,
                leadingIcon = Icons.Default.Link,
            )
        }
    }
}

@Composable
fun DiffItem(diff: SyncDiff) {
    val sp = LanSyncTheme.spacing
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = LanSyncTheme.containers.low,
            contentColor = scheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = LanSyncTheme.elevation.flat),
        border = BorderStroke(sp.hairline, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(sp.space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = diff.appInfo.packageName, size = sp.appIconMd)

            Spacer(Modifier.width(sp.space10))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diff.appInfo.appName,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = sp.space2),
                ) {
                    // 落后的是远端版本号：用 tertiary 琥珀高亮，呼应「可更新」徽标的色彩语义
                    val pair = stringResource(R.string.version_pair, diff.localVersion, diff.remoteVersion)
                    val remoteStart = pair.length - diff.remoteVersion.length
                    Text(
                        text = buildAnnotatedString {
                            append(pair)
                            if (remoteStart > 0 && pair.substring(remoteStart) == diff.remoteVersion) {
                                addStyle(SpanStyle(color = scheme.tertiary), remoteStart, pair.length)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            if (diff.appInfo.fileSize > 0) {
                RowSupportingText(text = formattedFileSize(diff.appInfo.fileSize))
            }
        }
    }
}
}

@Composable
fun UpdateItem(
    updateInfo: UpdateInfo,
    isSelected: Boolean,
    onToggleSelected: (String) -> Unit,
    onInstall: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    val remote = updateInfo.remoteApp
    LanSyncAppRow(
        title = remote.appName,
        packageName = remote.packageName,
        selected = isSelected,
        checked = isSelected,
        onCheckedChange = { onToggleSelected(remote.packageName) },
        accent = true,
        badges = { LanSyncBadge(LanSyncBadgeKind.Updatable) },
        supporting = {
            RowSupportingText(
                text = if (updateInfo.localApp != null) {
                    stringResource(R.string.version_pair, updateInfo.localApp.versionName, remote.versionName)
                } else {
                    remote.versionName
                },
                modifier = Modifier.padding(top = sp.space2),
                emphasis = true,
            )
            // 来源设备不再展示（同页只看一台设备）；该行只留安装包大小
            if (remote.fileSize > 0) {
                RowSupportingText(text = formattedFileSize(remote.fileSize))
            }
        },
        trailing = {
            RowActionButton(
                onClick = onInstall,
                label = stringResource(R.string.action_install),
                icon = Icons.Default.Download,
            )
        },
    )
}
