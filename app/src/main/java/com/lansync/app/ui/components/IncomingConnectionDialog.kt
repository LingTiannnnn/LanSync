package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.server.InMemoryPairingStore
import com.lansync.app.ui.theme.LanSyncMonoText
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.delay

/**
 * 配对请求 ModalBottomSheet：顶圆角 28（extraLarge）、系统返回键可关；倒计时结束自动拒绝。
 *
 * 倒计时 tick 与自动拒绝阈值取自 `InMemoryPairingStore.REQUEST_TIMEOUT_MS`（领域契约），
 * 步进时长走 `LanSyncTheme.motion.countdownTickMs`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingConnectionSheet(
    request: IncomingConnectRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LanSyncTheme.spacing
    val motion = LanSyncTheme.motion
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var remainingSeconds by remember(request.requestId) {
        mutableIntStateOf((InMemoryPairingStore.REQUEST_TIMEOUT_MS / 1000L).toInt())
    }

    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(motion.countdownTickMs)
            remainingSeconds--
        }
        if (remainingSeconds <= 0) {
            onReject()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.space24)
                .padding(bottom = sp.space32),
            verticalArrangement = Arrangement.spacedBy(sp.space16),
        ) {
            Text(
                text = stringResource(R.string.incoming_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Surface(
                color = LanSyncTheme.containers.low,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(sp.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(sp.deviceIcon),
                    )
                    Spacer(Modifier.width(sp.space12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = request.requesterName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.addr_ip_port,
                                request.requesterIp,
                                request.requesterPort,
                            ),
                            style = LanSyncMonoText.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val expiring = remainingSeconds <= EXPIRING_WARNING_SECONDS
                    StatusChip(
                        text = stringResource(R.string.incoming_countdown, remainingSeconds),
                        containerColor = if (expiring) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (expiring) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        leadingIcon = Icons.Default.Schedule,
                    )
                    if (expiring) {
                        // 倒计时每秒变化，把整个 chip 做成 liveRegion 会让 TalkBack 逐秒打断用户；
                        // 改为在进入「即将超时」时播报一次这一句状态变化。
                        Text(
                            text = stringResource(R.string.incoming_expiring),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.space12, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_reject))
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_accept))
                }
            }
        }
    }
}

/** 倒计时进入提醒档的秒数。 */
private const val EXPIRING_WARNING_SECONDS = 5
