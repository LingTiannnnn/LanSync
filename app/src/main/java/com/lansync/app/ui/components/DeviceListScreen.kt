package com.lansync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo

@Composable
fun DeviceListScreen(
    devices: List<DeviceInfo>,
    isRunning: Boolean,
    isScanningApps: Boolean,
    serverPort: Int,
    isConnecting: Boolean = false,
    connectingDeviceName: String? = null,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    operationMessage: String? = null,
    connectionError: String? = null,
    onToggleRunning: () -> Unit,
    onForceStartSync: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (DeviceInfo) -> Unit,
    onDisconnect: (DeviceInfo) -> Unit,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        StatusCard(
            isRunning = isRunning,
            isScanningApps = isScanningApps,
            serverPort = serverPort,
            isStarting = isStarting,
            isStopping = isStopping,
            operationMessage = operationMessage,
            onToggleRunning = onToggleRunning,
            onForceStartSync = onForceStartSync
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isConnecting && connectingDeviceName != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在连接 $connectingDeviceName...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (connectionError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = connectionError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "发现的设备",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新"
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isRunning) "正在搜索设备..." else "服务未启动",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
    onForceStartSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                        tint = if (isRunning) Color.Green else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isRunning) "LanSync 运行中" else "LanSync 已停止",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isRunning && serverPort > 0) {
                            Text(
                                text = "端口: $serverPort",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                if (isStarting || isStopping) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = operationMessage ?: "处理中...",
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

            if (isScanningApps && !isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onForceStartSync,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("强制启动同步")
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
        ConnectionState.DISCOVERED -> "已发现 - 点击连接"
        ConnectionState.CONNECTING -> "正在连接..."
        ConnectionState.CONNECTED -> "已连接 (${device.appList.size} 个应用)"
        ConnectionState.RECONNECTING -> device.connectionError ?: "正在尝试重新连接..."
        ConnectionState.ERROR -> "连接失败: ${device.connectionError ?: "未知错误"}"
        ConnectionState.CONNECTION_TIMEOUT -> "连接超时"
        ConnectionState.DISCONNECTED -> "已断开"
    }

    val statusColor = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.RECONNECTING -> Color(0xFFF57F17)
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.CONNECTION_TIMEOUT -> MaterialTheme.colorScheme.error
        ConnectionState.DISCOVERED -> MaterialTheme.colorScheme.outline
        ConnectionState.DISCONNECTED -> Color.Gray
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (state == ConnectionState.CONNECTED) 4.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
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
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

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
                        modifier = Modifier.padding(top = 2.dp)
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
                                color = Color(0xFF4CAF50).copy(alpha = 0.2f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "在线",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2E7D32),
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                when (state) {
                    ConnectionState.DISCOVERED, ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.CONNECTION_TIMEOUT -> {
                        Button(onClick = onConnect) {
                            Icon(Icons.Default.Link, contentDescription = "连接", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("连接")
                        }
                    }
                    ConnectionState.CONNECTING -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("连接中")
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = "断开", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("断开")
                        }
                    }
                    ConnectionState.RECONNECTING -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = "断开", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("断开")
                        }
                    }
                }
            }
        }
    }
}
