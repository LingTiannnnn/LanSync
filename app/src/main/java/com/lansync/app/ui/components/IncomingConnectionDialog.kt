package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lansync.app.R
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.server.InMemoryPairingStore
import com.lansync.app.ui.theme.LanSyncMetrics
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.delay

/**
 * 配对请求 ModalBottomSheet（COMPONENTS.md §10 / SCREENS O1）。
 * 顶圆角 28、系统返回键可关；倒计时结束自动拒绝。
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var remainingSeconds by remember(request.requestId) {
        mutableIntStateOf((InMemoryPairingStore.REQUEST_TIMEOUT_MS / 1000L).toInt())
    }

    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds <= 0) {
            onReject()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = sp.radiusXl,
            topEnd = sp.radiusXl,
        ),
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
                    Box(
                        modifier = Modifier
                            .size(LanSyncMetrics.deviceIcon)
                            .padding(sp.none),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(sp.iconXxl),
                        )
                    }
                    Spacer(Modifier.width(sp.space12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = request.requesterName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = "${request.requesterIp}:${request.requesterPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    StatusChip(
                        text = stringResource(R.string.incoming_countdown, remainingSeconds),
                        containerColor = if (remainingSeconds <= 5) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (remainingSeconds <= 5) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        leadingIcon = Icons.Default.Schedule,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.space12, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_reject))
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.heightIn(min = LanSyncMetrics.minTouchTarget),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_accept), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 兼容旧名。 */
@Composable
fun IncomingConnectionDialog(
    request: IncomingConnectRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    IncomingConnectionSheet(
        request = request,
        onAccept = onAccept,
        onReject = onReject,
        onDismiss = onDismiss,
    )
}
