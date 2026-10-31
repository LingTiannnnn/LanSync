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
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.ui.theme.LanSyncMonoText
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * S1 设备列表内容：本机身份条 + 连接失败反馈 + MeshHint + 已连接 / 发现中 分区 + 空态 CTA。
 *
 * 状态色与状态文案走 [deviceStatusVisual]（M3 容器角色配对），
 * 不再用 `statusColor.copy(alpha = 0.15f)` 自造底 —— 那种写法把 `outline`（3:1 非文本角色）
 * 降格成 11sp 正文色，亮色主题下对比度只有 3.3:1。
 */
@Composable
fun DeviceListScreen(
    devices: List<DeviceInfo>,
    isRunning: Boolean,
    serverPort: Int,
    isStarting: Boolean,
    isStopping: Boolean,
    connectionNotice: String?,
    updatableCount: Int,
    localAppCount: Int,
    onToggleRunning: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (DeviceInfo) -> Unit,
    onDisconnect: (DeviceInfo) -> Unit,
    onDismissError: () -> Unit,
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
        IdentityStrip(
            deviceName = android.os.Build.MODEL,
            isRunning = isRunning,
            serverPort = serverPort,
            isStarting = isStarting,
            isStopping = isStopping,
            onToggleRunning = onToggleRunning,
            updatableCount = updatableCount,
            connectedCount = connected.size,
            localAppCount = localAppCount,
        )

        if (connectionNotice != null) {
            FeedbackBanner(
                text = connectionNotice,
                tone = FeedbackTone.Failure,
                dismissible = true,
                onDismiss = onDismissError,
            )
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 顶栏已消费 statusBars、底栏已消费 navigationBars：此处不得再叠 insets
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
                        )
                    }
                }

                if (devices.isEmpty()) {
                    item(key = "searching") {
                        EmptyState(
                            icon = Icons.Default.WifiTethering,
                            title = stringResource(R.string.devices_searching),
                            actionLabel = stringResource(R.string.action_refresh),
                            onAction = onRefresh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(sp.space24),
                        )
                    }
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
    val visual = deviceStatusVisual(device)
    val connecting = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING

    Card(
        modifier = modifier.heightIn(min = sp.deviceCardMin),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = LanSyncTheme.containers.low,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = LanSyncTheme.elevation.card),
    ) {
        Column(modifier = Modifier.padding(sp.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(sp.deviceIcon),
                    contentAlignment = Alignment.Center,
                ) {
                    if (connecting) {
                        LanSyncSpinner(color = visual.accent)
                    } else {
                        Icon(
                            imageVector = when (state) {
                                ConnectionState.CONNECTED -> Icons.Default.Link
                                ConnectionState.ERROR, ConnectionState.CONNECTION_TIMEOUT ->
                                    Icons.Default.Warning
                                else -> Icons.Default.PhoneAndroid
                            },
                            contentDescription = null,
                            tint = visual.accent,
                            modifier = Modifier.size(sp.iconFeature),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(sp.space12))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = sp.space2),
                    ) {
                        Text(
                            text = stringResource(R.string.addr_ip_port, device.ipAddress, device.port),
                            style = LanSyncMonoText.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }

                    StatusChip(
                        text = visual.text,
                        containerColor = visual.container,
                        contentColor = visual.content,
                        modifier = Modifier.padding(top = sp.space2),
                    )

                    visual.detail?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = sp.space4),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(sp.space8))

                when (state) {
                    ConnectionState.DISCOVERED,
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR,
                    ConnectionState.CONNECTION_TIMEOUT,
                    -> Button(
                        onClick = onConnect,
                        modifier = Modifier.heightIn(min = sp.minTouchTarget),
                    ) {
                        // 与「断开」按钮镜像同尺寸：图标 + 文案 + 默认边距（RowActionButton 是行内紧凑形态，窄一圈）
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(sp.iconMd),
                        )
                        Spacer(modifier = Modifier.width(sp.space4))
                        Text(stringResource(R.string.action_connect))
                    }

                    ConnectionState.CONNECTING -> InProgressButton(
                        label = stringResource(R.string.action_connecting),
                    )

                    ConnectionState.CONNECTED, ConnectionState.RECONNECTING -> OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.heightIn(min = sp.minTouchTarget),
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
