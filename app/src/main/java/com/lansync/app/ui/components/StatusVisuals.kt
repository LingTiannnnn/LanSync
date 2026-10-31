package com.lansync.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lansync.app.R
import com.lansync.app.data.model.ConnectionState
import com.lansync.app.data.model.DeviceInfo
import com.lansync.app.ui.string.connectionFailureText
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 连接状态的视觉与文案唯一映射（ARCH §13.4「连接状态色全走 colorScheme 角色」）。
 *
 * 容器色一律取 M3 的 `*Container` / `on*Container` 配对，**不得**再用
 * `color.copy(alpha = …)` 自造淡化底：那样 `outline`（3:1 非文本角色）会被降格成正文色，
 * 亮色主题下小字号状态文字掉出 WCAG AA。
 */
@Immutable
data class DeviceStatusVisual(
    val container: Color,
    val content: Color,
    val accent: Color,
    val text: String,

    /** 连接失败的可读原因（typed `ConnectionFailure` → 资源）；无失败时为 null。 */
    val detail: String?,
)

@Composable
@ReadOnlyComposable
fun deviceStatusVisual(device: DeviceInfo): DeviceStatusVisual {
    val scheme = MaterialTheme.colorScheme
    val containers = LanSyncTheme.containers
    val state = device.connectionState

    val tint: Triple<Color, Color, Color> = when (state) {
        ConnectionState.CONNECTED ->
            Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
            Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        ConnectionState.ERROR, ConnectionState.CONNECTION_TIMEOUT ->
            Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
        ConnectionState.DISCOVERED ->
            Triple(containers.high, scheme.onSurfaceVariant, scheme.onSurfaceVariant)
        ConnectionState.DISCONNECTED ->
            Triple(containers.high, scheme.onSurfaceVariant, scheme.onSurfaceVariant)
    }

    val text = when (state) {
        ConnectionState.DISCOVERED -> stringResource(R.string.device_state_discovered)
        ConnectionState.CONNECTING -> stringResource(R.string.device_state_connecting)
        ConnectionState.CONNECTED ->
            stringResource(R.string.device_state_connected, device.appList.size)
        ConnectionState.RECONNECTING ->
            stringResource(R.string.device_state_reconnecting)
        ConnectionState.ERROR ->
            stringResource(R.string.device_state_error)
        ConnectionState.CONNECTION_TIMEOUT ->
            stringResource(R.string.device_state_timeout)
        ConnectionState.DISCONNECTED -> stringResource(R.string.device_state_disconnected)
    }

    return DeviceStatusVisual(
        container = tint.first,
        content = tint.second,
        accent = tint.third,
        text = text,
        // 重连/超时：琥珀/红底状态条已表达状态，再叠实时重试计数或「设备已离线」纯文字是冗余
        detail = when (state) {
            ConnectionState.RECONNECTING, ConnectionState.CONNECTION_TIMEOUT -> null
            else -> device.connectionFailure?.let { connectionFailureText(it) }
        },
    )
}
