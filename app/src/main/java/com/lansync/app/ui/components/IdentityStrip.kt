package com.lansync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncMetrics
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 设备首页身份条（COMPONENTS.md §2）：primaryContainer 贴 statusBars，
 * 本机名 + 运行开关 + HeroStats 三格。
 */
@Composable
fun IdentityStrip(
    deviceName: String,
    isRunning: Boolean,
    serverPort: Int,
    isStarting: Boolean,
    isStopping: Boolean,
    onToggleRunning: () -> Unit,
    updatableCount: Int,
    connectedCount: Int,
    localAppCount: Int,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = sp.space16, vertical = sp.space12),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(LanSyncMetrics.deviceIcon)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.medium,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(sp.iconXl),
                )
            }
            Spacer(Modifier.width(sp.space12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isRunning && serverPort > 0) {
                        stringResource(
                            R.string.identity_port_line,
                            stringResource(R.string.status_running),
                            serverPort,
                        )
                    } else {
                        stringResource(R.string.status_stopped)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isRunning,
                onCheckedChange = { if (!isStarting && !isStopping) onToggleRunning() },
                enabled = !isStarting && !isStopping,
            )
        }

        Spacer(Modifier.height(sp.space12))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeroStat(value = updatableCount, label = stringResource(R.string.hero_stat_updatable))
            HeroStat(value = connectedCount, label = stringResource(R.string.hero_stat_connected))
            HeroStat(value = localAppCount, label = stringResource(R.string.hero_stat_local_apps))
        }
    }
}

@Composable
private fun HeroStat(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Column(
        modifier = modifier.padding(horizontal = sp.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
