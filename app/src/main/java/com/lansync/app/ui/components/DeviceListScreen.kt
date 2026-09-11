package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.ui.theme.LanSyncMetrics
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * S1 设备列表内容：MeshHint + 已连接 / 发现中 分区 + 空态 CTA。
 * 本机身份条见 [IdentityStrip]（由 MainActivity 挂在顶区）。
 */
@Composable
fun DeviceListScreen(
    devices: List<DeviceInfo>,
    isRunning: Boolean,
    isScanningApps: Boolean,
    serverPort: Int,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    operationMessage: String? = null,
    connectionError: String? = null,
    onToggleRunning: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (DeviceInfo) -> Unit,
    onDisconnect: (DeviceInfo) -> Unit,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    val connected = devices.filter {
        it.connectionState == ConnectionState.CONNECTED || it.connectionState == ConnectionState.RECONNECTING
    }
    val discovering = devices.filter {
        it.connectionState != ConnectionState.CONNECTED && it.connectionState != ConnectionState.RECONNECTING
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (connectionError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.space16, vertical = sp.space8),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(sp.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(sp.iconLg),
                    )
                    Spacer(Modifier.width(sp.space10))
                    Text(
                        text = connectionError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                }
            }
        }

        if (!isRunning && devices.isEmpty()) {
            EmptyState(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.devices_service_stopped),
                body = stringResource(R.string.status_stopped),
                actionLabel = stringResource(R.string.devices_empty_cta),
                onAction = onToggleRunning,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Scaffold paddingValues 已含 NavigationBar（含手势区）高度，此处不得再叠 navigationBars
            contentPadding = PaddingValues(
                start = sp.space16,
                end = sp.space16,
                top = sp.space8,
                bottom = sp.space16,
            ),
            verticalArrangement = Arrangement.spacedBy(sp.space8),
        ) {
            if (devices.isNotEmpty()) {
                item(key = "mesh") {
                    MeshHint(
                        onlineCount = connected.size,
                        totalVisible = devices.size,
                    )
                }
            }

            if (connected.isNotEmpty()) {
                item(key = "header_connected") {
                    SectionHeader(
                        title = stringResource(R.string.devices_section_connected),
                        icon = Icons.Default.Link,
                        count = connected.size,
                    )
                }
                items(connected, key = { it.displayKey }) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { onConnect(device) },
                        onDisconnect = { onDisconnect(device) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (discovering.isNotEmpty()) {
                item(key = "header_discovering") {
                    SectionHeader(
                        title = stringResource(R.string.devices_section_discovering),
                        icon = Icons.Default.Radar,
                        count = discovering.size,
                    )
                }
                items(discovering, key = { it.displayKey }) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { onConnect(device) },
                        onDisconnect = { onDisconnect(device) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (devices.isEmpty()) {
                item(key = "searching") {
                    EmptyState(
                        icon = Icons.Default.WifiTethering,
                        title = stringResource(R.string.devices_searching),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(sp.space24),
                    )
                }
            }

            if (isScanningApps) {
                item(key = "scanning") {
                    StatusChip(
                        text = stringResource(R.string.top_bar_scanning),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    isRunning: Boolean,
    isScanningApps: Boolean,
    serverPort: Int,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    operationMessage: String? = null,
    onToggleRunning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.space16),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isRunning) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(modifier = Modifier.width(sp.space8))
                    Column {
                        Text(
                            text = stringResource(
                                if (isRunning) R.string.status_running else R.string.status_stopped,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (isRunning && serverPort > 0) {
                            Text(
                                text = stringResource(R.string.status_port, serverPort),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (isStarting || isStopping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(sp.iconLg),
                            strokeWidth = sp.strokeThin,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(sp.space8))
                        Text(
                            text = operationMessage ?: stringResource(R.string.status_processing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { onToggleRunning() },
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceInfo,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    val state = device.connectionState
    val containerColor = LanSyncTheme.containers.low

    val statusText = when (state) {
        ConnectionState.DISCOVERED -> stringResource(R.string.device_state_discovered)
        ConnectionState.CONNECTING -> stringResource(R.string.device_state_connecting)
        ConnectionState.CONNECTED -> stringResource(R.string.device_state_connected, device.appList.size)
        ConnectionState.RECONNECTING -> device.connectionError
            ?: stringResource(R.string.device_state_reconnecting)
        ConnectionState.ERROR -> stringResource(
            R.string.device_state_error,
            device.connectionError ?: stringResource(R.string.error_unknown),
        )
        ConnectionState.CONNECTION_TIMEOUT -> stringResource(R.string.device_state_timeout)
        ConnectionState.DISCONNECTED -> stringResource(R.string.device_state_disconnected)
    }

    val statusColor = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.CONNECTION_TIMEOUT -> MaterialTheme.colorScheme.error
        ConnectionState.DISCOVERED -> MaterialTheme.colorScheme.outline
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.heightIn(min = LanSyncMetrics.deviceCardMin),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (state == ConnectionState.CONNECTED) sp.space4 else sp.hairline,
        ),
    ) {
        Column(modifier = Modifier.padding(sp.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(sp.space48),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(sp.iconXxl),
                            strokeWidth = sp.strokeThick,
                            color = statusColor,
                        )
                    } else {
                        Icon(
                            imageVector = when (state) {
                                ConnectionState.CONNECTED -> Icons.Default.Link
                                ConnectionState.ERROR -> Icons.Default.Warning
                                ConnectionState.CONNECTION_TIMEOUT -> Icons.Default.Warning
                                else -> Icons.Default.PhoneAndroid
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(sp.iconFeature),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(sp.space12))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = sp.space2),
                    ) {
                        Text(
                            text = "${device.ipAddress}:${device.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        if (state == ConnectionState.CONNECTED) {
                            Spacer(Modifier.width(sp.space6))
                            StatusChip(
                                text = stringResource(R.string.device_online_badge),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    StatusChip(
                        text = statusText,
                        containerColor = statusColor.copy(alpha = 0.15f),
                        contentColor = statusColor,
                        modifier = Modifier.padding(top = sp.space2),
                    )
                }

                Spacer(modifier = Modifier.width(sp.space8))

                when (state) {
                    ConnectionState.DISCOVERED,
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR,
                    ConnectionState.CONNECTION_TIMEOUT,
                    -> {
                        Button(onClick = onConnect) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(sp.iconMd),
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_connect))
                        }
                    }
                    ConnectionState.CONNECTING -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(sp.iconSm),
                                strokeWidth = sp.strokeThin,
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_connecting))
                        }
                    }
                    ConnectionState.CONNECTED,
                    ConnectionState.RECONNECTING,
                    -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(sp.iconMd),
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                }
            }
        }
    }
}
