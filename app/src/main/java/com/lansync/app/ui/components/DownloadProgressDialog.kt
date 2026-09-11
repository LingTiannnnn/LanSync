package com.lansync.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.lansync.app.R
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DownloadProgressDialog(
    progress: DownloadInstallController.DownloadProgress,
    onDismiss: () -> Unit,
    onInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var installClicked by remember { mutableStateOf(false) }

    LaunchedEffect(installClicked) {
        if (installClicked) {
            delay(5000L)
            installClicked = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress.status != DownloadInstallController.DownloadProgress.Status.DOWNLOADING) onDismiss() },
        modifier = modifier,
        icon = {
            Icon(
                imageVector = when (progress.status) {
                    DownloadInstallController.DownloadProgress.Status.DOWNLOADING -> Icons.Default.Downloading
                    DownloadInstallController.DownloadProgress.Status.VERIFYING -> Icons.Default.VerifiedUser
                    DownloadInstallController.DownloadProgress.Status.COMPLETED -> Icons.Default.CheckCircle
                    DownloadInstallController.DownloadProgress.Status.FAILED -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when (progress.status) {
                    DownloadInstallController.DownloadProgress.Status.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        },
        title = {
            Text(
                text = when (progress.status) {
                    DownloadInstallController.DownloadProgress.Status.DOWNLOADING -> stringResource(R.string.download_state_downloading)
                    DownloadInstallController.DownloadProgress.Status.VERIFYING -> stringResource(R.string.download_state_verifying)
                    DownloadInstallController.DownloadProgress.Status.COMPLETED -> stringResource(R.string.download_state_completed)
                    DownloadInstallController.DownloadProgress.Status.FAILED -> stringResource(R.string.download_state_failed)
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                Text(
                    text = progress.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                if (progress.status != DownloadInstallController.DownloadProgress.Status.FAILED) {
                    LinearProgressIndicator(
                        progress = (progress.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.download_progress_percent, progress.progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (progress.status == DownloadInstallController.DownloadProgress.Status.COMPLETED) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.space8)) {
                    if (onInstall != null && !installClicked) {
                        OutlinedButton(onClick = {
                            installClicked = true
                            onInstall()
                        }) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                            Spacer(Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_install))
                        }
                    } else if (installClicked) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(sp.iconSm),
                                strokeWidth = sp.strokeThin,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(sp.space4))
                            Text(stringResource(R.string.action_installing))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            } else if (progress.status == DownloadInstallController.DownloadProgress.Status.FAILED) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
    )
}

@Composable
fun InstallStatusSnackbar(
    status: DownloadInstallController.InstallStatus,
    onDismiss: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    // keyed on status：换一条安装反馈时必须重置可见性，否则首条关闭后后续不再显示
    var isVisible by remember(status) { mutableStateOf(true) }
    val offsetX = remember(status) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(status) {
        delay(10_000L)
        if (isVisible) {
            scope.launch {
                offsetX.animateTo(1000f, tween(300))
                isVisible = false
                onDismiss()
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200))
    ) {
        Box(
            modifier = Modifier
                .padding(sp.space16)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(offsetX.value) > 200f) {
                                scope.launch {
                                    offsetX.animateTo(
                                        if (offsetX.value > 0) 1000f else -1000f,
                                        tween(300)
                                    )
                                    isVisible = false
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    offsetX.animateTo(0f, tween(300))
                                }
                            }
                        }
                    ) { _, dragAmount ->
                        scope.launch {
                            val newValue =
                                (offsetX.value + dragAmount).coerceIn(-500f, 500f)
                            offsetX.snapTo(newValue)
                        }
                    }
                }
        ) {
            when (status) {
                is DownloadInstallController.InstallStatus.Installing -> {
                    Surface(
                        shape = RoundedCornerShape(sp.radiusXs),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = sp.space6
                    ) {
                        Row(
                            modifier = Modifier.padding(sp.space12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(sp.iconXl),
                                strokeWidth = sp.strokeThin,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(sp.space12))
                            Text(
                                text = stringResource(R.string.install_installing, status.packageName),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is DownloadInstallController.InstallStatus.Success -> {
                    Surface(
                        shape = RoundedCornerShape(sp.radiusXs),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shadowElevation = sp.space6
                    ) {
                        Row(
                            modifier = Modifier.padding(sp.space12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(sp.space12))
                            Text(
                                text = stringResource(R.string.install_success, status.packageName),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is DownloadInstallController.InstallStatus.Failed -> {
                    Surface(
                        shape = RoundedCornerShape(sp.radiusXs),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shadowElevation = sp.space6
                    ) {
                        Row(
                            modifier = Modifier.padding(sp.space12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(sp.space12))
                            Text(
                                text = status.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    offsetX.animateTo(1000f, tween(300))
                                    isVisible = false
                                    onDismiss()
                                }
                            }) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }
        }
    }
}
