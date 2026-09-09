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
import androidx.compose.ui.text.font.FontWeight
import com.lansync.app.R
import com.lansync.app.data.server.InMemoryPairingStore
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.delay

@Composable
fun IncomingConnectionDialog(
    request: IncomingConnectRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    var remainingSeconds by remember { mutableIntStateOf(InMemoryPairingStore.REQUEST_TIMEOUT_MS.toInt() / 1000) }

    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds <= 0) {
            onReject()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.incoming_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.space8)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(sp.radiusMd),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(sp.space12),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(sp.space8))
                        Column {
                            Text(
                                request.requesterName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "${request.requesterIp}:${request.requesterPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Surface(
                    color = if (remainingSeconds <= 5) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(sp.radiusMd),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(sp.space12), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (remainingSeconds <= 5) {
                                Icon(
                                    Icons.Default.Warning,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(sp.iconSm)
                                )
                                Spacer(Modifier.width(sp.space4))
                            } else {
                                Icon(
                                    Icons.Default.Schedule,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(sp.iconSm)
                                )
                                Spacer(Modifier.width(sp.space4))
                            }
                            Text(
                                text = stringResource(R.string.incoming_countdown, remainingSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (remainingSeconds <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(sp.iconMd))
                Spacer(Modifier.width(sp.space4))
                Text(stringResource(R.string.action_accept))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Icon(Icons.Default.Cancel, null, modifier = Modifier.size(sp.iconMd))
                Spacer(Modifier.width(sp.space4))
                Text(stringResource(R.string.action_reject))
            }
        }
    )
}
