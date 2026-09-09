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
import com.lansync.app.ui.theme.LanSyncTheme

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
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(sp.space16)
    ) {
        StatusCard(
            isRunning = isRunning,
            isScanningApps = isScanningApps,
            serverPort = serverPort,
            isStarting = isStarting,
            isStopping = isStopping,
            operationMessage = operationMessage,
            onToggleRunning = onToggleRunning
        )

        Spacer(modifier = Modifier.height(sp.space16))

        if (connectionError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(sp.space12),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(sp.iconLg)
                    )
                    Spacer(Modifier.width(sp.space10))
                    Text(
                        text = connectionError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(sp.iconXl)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            modifier = Modifier.size(sp.iconSm)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(sp.space8))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.devices_section_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh)
                )
            }
        }

        Spacer(modifier = Modifier.height(sp.space8))

        if (devices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(sp.space24),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(sp.iconEmpty)
                    )
                    Spacer(modifier = Modifier.height(sp.space8))
                    Text(
                        text = stringResource(
                            if (isRunning) R.string.devices_searching else R.string.devices_service_stopped
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                items(devices, key = { it.displayKey }) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { onConnect(device) },
                        onDisconnect = { onDisconnect(device) },
                        modifier = Modifier.fillMaxWidth()
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
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.space16)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isRunning) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(sp.space8))
                    Column {
                        Text(
                            text = stringResource(
                                if (isRunning) R.string.status_running else R.string.status_stopped
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (isRunning && serverPort > 0) {
                            Text(
                                text = stringResource(R.string.status_port, serverPort),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                if (isStarting || isStopping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(sp.iconLg),
                            strokeWidth = sp.strokeThin,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(sp.space8))
                        Text(
                            text = operationMessage ?: stringResource(R.string.status_processing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { onToggleRunning() }
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
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    val state = device.connectionState

    val containerColor = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.CONNECTION_TIMEOUT -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.DISCOVERED -> MaterialTheme.colorScheme.surface
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }

    val statusText = when (state) {
        ConnectionState.DISCOVERED -> stringResource(R.string.device_state_discovered)
        ConnectionState.CONNECTING -> stringResource(R.string.device_state_connecting)
        ConnectionState.CONNECTED -> stringResource(R.string.device_state_connected, device.appList.size)
        ConnectionState.RECONNECTING -> device.connectionError ?: stringResource(R.string.device_state_reconnecting)
        ConnectionState.ERROR -> stringResource(
            R.string.device_state_error,
            device.connectionError ?: stringResource(R.string.error_unknown)
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
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (state == ConnectionState.CONNECTED) sp.space4 else sp.hairline
        )
    ) {
        Column(
            modifier = Modifier.padding(sp.space16)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(sp.space48),
                    contentAlignment = Alignment.Center
                ) {
                    if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(sp.iconXxl),
                            strokeWidth = sp.strokeThick,
                            color = statusColor
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
                            modifier = Modifier.size(sp.iconFeature)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(sp.space12))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = sp.space2)
                    ) {
                        Text(
                            text = "${device.ipAddress}:${device.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (state == ConnectionState.CONNECTED) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(R.string.device_online_badge),
                                    modifier = Modifier.padding(horizontal = sp.space6, vertical = sp.hairline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier.padding(horizontal = sp.space8, vertical = sp.space2),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(sp.space8))

                when (state) {
                    ConnectionState.DISCOVERED, ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.CONNECTION_TIMEOUT -> {
                        Button(onClick = onConnect) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(sp.iconMd)
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_connect))
                        }
                    }
                    ConnectionState.CONNECTING -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(sp.iconSm),
                                strokeWidth = sp.strokeThin
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_connecting))
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(sp.iconMd)
                            )
                            Spacer(modifier = Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                    ConnectionState.RECONNECTING -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(sp.iconMd)
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
