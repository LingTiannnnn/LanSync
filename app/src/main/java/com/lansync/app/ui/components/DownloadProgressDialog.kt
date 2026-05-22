package com.lansync.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lansync.app.data.repository.AppRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DownloadProgressDialog(
    progress: AppRepository.DownloadProgress,
    onDismiss: () -> Unit,
    onInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var installClicked by remember { mutableStateOf(false) }

    LaunchedEffect(installClicked) {
        if (installClicked) {
            delay(5000L)
            installClicked = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress.status != AppRepository.DownloadProgress.Status.DOWNLOADING) onDismiss() },
        modifier = modifier,
        icon = {
            Icon(
                imageVector = when (progress.status) {
                    AppRepository.DownloadProgress.Status.DOWNLOADING -> Icons.Default.Downloading
                    AppRepository.DownloadProgress.Status.VERIFYING -> Icons.Default.VerifiedUser
                    AppRepository.DownloadProgress.Status.COMPLETED -> Icons.Default.CheckCircle
                    AppRepository.DownloadProgress.Status.FAILED -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when (progress.status) {
                    AppRepository.DownloadProgress.Status.DOWNLOADING, AppRepository.DownloadProgress.Status.VERIFYING -> MaterialTheme.colorScheme.primary
                    AppRepository.DownloadProgress.Status.COMPLETED -> MaterialTheme.colorScheme.primary
                    AppRepository.DownloadProgress.Status.FAILED -> MaterialTheme.colorScheme.error
                }
            )
        },
        title = {
            Text(
                text = when (progress.status) {
                    AppRepository.DownloadProgress.Status.DOWNLOADING -> "下载中"
                    AppRepository.DownloadProgress.Status.VERIFYING -> "校验中"
                    AppRepository.DownloadProgress.Status.COMPLETED -> "下载完成"
                    AppRepository.DownloadProgress.Status.FAILED -> "下载失败"
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = progress.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                if (progress.status != AppRepository.DownloadProgress.Status.FAILED) {
                    LinearProgressIndicator(
                        progress = (progress.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = "${progress.progress}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (progress.status == AppRepository.DownloadProgress.Status.COMPLETED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onInstall != null && !installClicked) {
                        OutlinedButton(onClick = {
                            installClicked = true
                            onInstall()
                        }) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("安装")
                        }
                    } else if (installClicked) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("安装中...")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("确定")
                    }
                }
            } else if (progress.status == AppRepository.DownloadProgress.Status.FAILED) {
                TextButton(onClick = onDismiss) {
                    Text("确定")
                }
            }
        }
    )
}

@Composable
fun InstallStatusSnackbar(
    status: AppRepository.InstallStatus,
    onDismiss: () -> Unit
) {
    var isVisible by remember { mutableStateOf(true) }
    val offsetX = remember { Animatable(0f) }
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
                .padding(16.dp)
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
                is AppRepository.InstallStatus.Installing -> {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "正在安装 ${status.packageName}...",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is AppRepository.InstallStatus.Success -> {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${status.packageName} 安装已发起",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is AppRepository.InstallStatus.Failed -> {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
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
                                Text("关闭")
                            }
                        }
                    }
                }
            }
        }
    }
}
