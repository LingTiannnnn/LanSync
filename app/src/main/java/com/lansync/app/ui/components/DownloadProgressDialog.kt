package com.lansync.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lansync.app.R
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 下载进度对话框。
 *
 * 「安装中」状态由 `UiState.installStatus` 驱动（[installing]），
 * 不再在组件里用 5 秒计时猜测安装结果 —— 安装是外部 Intent 行为，计时不可靠。
 *
 * 下载中唯一的退出是 [onCancel]（真正中断底层下载）；点外部/返回键不关闭，避免误触丢进度。
 */
@Composable
fun DownloadProgressDialog(
    progress: DownloadInstallController.DownloadProgress,
    installing: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    val status = progress.status
    val failed = status == DownloadInstallController.DownloadProgress.Status.FAILED
    val completed = status == DownloadInstallController.DownloadProgress.Status.COMPLETED

    AlertDialog(
        onDismissRequest = {
            if (status != DownloadInstallController.DownloadProgress.Status.DOWNLOADING) onDismiss()
        },
        modifier = modifier,
        icon = {
            Icon(
                imageVector = when (status) {
                    DownloadInstallController.DownloadProgress.Status.DOWNLOADING -> Icons.Default.Downloading
                    DownloadInstallController.DownloadProgress.Status.VERIFYING -> Icons.Default.VerifiedUser
                    DownloadInstallController.DownloadProgress.Status.COMPLETED -> Icons.Default.CheckCircle
                    DownloadInstallController.DownloadProgress.Status.FAILED -> Icons.Default.Error
                },
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(
                    when (status) {
                        DownloadInstallController.DownloadProgress.Status.DOWNLOADING ->
                            R.string.download_state_downloading
                        DownloadInstallController.DownloadProgress.Status.VERIFYING ->
                            R.string.download_state_verifying
                        DownloadInstallController.DownloadProgress.Status.COMPLETED ->
                            R.string.download_state_completed
                        DownloadInstallController.DownloadProgress.Status.FAILED ->
                            R.string.download_state_failed
                    }
                )
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                Text(
                    text = progress.packageName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (!failed) {
                    LanSyncProgressBar(
                        progress = (progress.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
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
            when {
                !completed && !failed -> {
                    // 下载中：取消真正中断底层下载（onCancel → controller.cancelDownload）
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                failed -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok))
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(sp.space8)) {
                    if (installing) {
                        InProgressButton(label = stringResource(R.string.action_installing))
                    } else if (onInstall != null) {
                        RowActionButton(
                            onClick = onInstall,
                            label = stringResource(R.string.action_install),
                            icon = Icons.Default.InstallMobile,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            }
        }
    )
}

/**
 * 覆盖层进度条的宽度比例（登记过的例外，不是随手值）：
 * 跑马灯只用于表达「在进行中」，比标题行短一截才不会与上下两行文字抢视线；
 * 高度、颜色、轨道色由 `LanSyncProgressBar` 统一，这里只保留宽度选择。
 */
private const val OVERLAY_PROGRESS_WIDTH = 0.7f

/** 遮罩内的加载卡（首扫与全屏等待共用）；spinner、进度条与文案走同一套规格。 */
@Composable
fun OverlayProgressCard(
    title: String,
    body: String,
    footer: String,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(sp.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LanSyncSpinner(size = sp.iconEmpty)
        Spacer(Modifier.height(sp.space24))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(sp.space12))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(sp.space24))
        LanSyncProgressBar(modifier = Modifier.fillMaxWidth(OVERLAY_PROGRESS_WIDTH))
        Spacer(Modifier.height(sp.space16))
        Text(
            text = footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
