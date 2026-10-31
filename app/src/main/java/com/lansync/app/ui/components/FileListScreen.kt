package com.lansync.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.transfer.LanSyncClient
import com.lansync.app.ui.format.formattedFileSize
import com.lansync.app.ui.theme.LanSyncMonoText
import com.lansync.app.ui.theme.LanSyncTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间戳显示格式（SimpleDateFormat 技术格式，非 UI 文案，故不入 strings.xml）。 */
private const val TIME_PATTERN = "MM-dd HH:mm"

@Composable
fun FileListScreen(
    files: List<LanSyncClient.DownloadedFileInfo>,
    selectedFiles: List<String>,
    onSelectedFilesChange: (List<String>) -> Unit,
    appNames: Map<String, String>,
    onInstall: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onRefresh: () -> Unit,
    onOpenUpdatesTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.files_empty_title),
                body = stringResource(R.string.files_empty_hint),
                actionLabel = stringResource(R.string.files_empty_cta),
                onAction = onOpenUpdatesTab,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
            )
        } else {
            val totalBytes = files.sumOf { it.fileSize }
            SummaryCard(
                primary = stringResource(R.string.files_summary_primary, files.size),
                secondary = stringResource(
                    R.string.files_summary_secondary,
                    formattedFileSize(totalBytes),
                ),
                actionLabel = stringResource(R.string.cd_refresh_files),
                onAction = onRefresh,
                modifier = Modifier.padding(horizontal = sp.space16, vertical = sp.space8),
            )

            LazyColumn(
                // weight(1f) 把批量条压到列表底部（贴底栏上方），与远程/同步页同一位置语义
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = sp.space16,
                    end = sp.space16,
                    top = sp.space8,
                    bottom = sp.space16,
                ),
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                items(files, key = { it.fileName }) { file ->
                    DownloadedFileItem(
                        file = file,
                        appName = appNames[file.packageName] ?: file.packageName,
                        isSelected = file.fileName in selectedFiles,
                        onSelect = {
                            onSelectedFilesChange(
                                if (file.fileName in selectedFiles) {
                                    selectedFiles - file.fileName
                                } else {
                                    selectedFiles + file.fileName
                                }
                            )
                        },
                        onSave = { onSave(file.fileName) },
                        onInstall = { onInstall(file.fileName) }
                    )
                }
            }

            if (selectedFiles.isNotEmpty()) {
                // 与远程/同步页一致：全选/取消全选开关在顶栏，批量条只留计数 + 主操作
                BatchBar(
                    label = stringResource(R.string.selection_count_files, selectedFiles.size),
                    actionLabel = stringResource(R.string.action_delete),
                    onAction = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.files_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.files_delete_confirm_message, selectedFiles.size))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(selectedFiles)
                        onSelectedFilesChange(emptyList())
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DownloadedFileItem(
    file: LanSyncClient.DownloadedFileInfo,
    appName: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onInstall: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    LanSyncAppRow(
        title = appName,
        packageName = file.packageName,
        selected = isSelected,
        checked = isSelected,
        onCheckedChange = { onSelect() },
        icon = { AppIcon(packageName = file.packageName, size = sp.appIconSm) },
        badges = {
            if (file.isSplitApk) LanSyncBadge(LanSyncBadgeKind.Split)
        },
        supporting = {
            RowSupportingText(
                text = stringResource(
                    R.string.file_meta_line,
                    formattedFileSize(file.fileSize),
                    formatTime(file.lastModified),
                ),
                modifier = Modifier.padding(top = sp.space2),
            )
            Text(
                text = file.packageName,
                style = LanSyncMonoText.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(sp.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadedFileSaveAction(onSave = onSave)
                RowActionButton(
                    onClick = onInstall,
                    label = stringResource(R.string.action_install),
                    icon = Icons.Default.InstallMobile,
                )
            }
        },
    )
}

@Composable
private fun DownloadedFileSaveAction(onSave: () -> Unit, modifier: Modifier = Modifier) {
    val sp = LanSyncTheme.spacing
    OutlinedButton(
        onClick = onSave,
        modifier = modifier.heightIn(min = sp.minTouchTarget),
        contentPadding = PaddingValues(horizontal = sp.space12),
    ) {
        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(sp.iconSm))
        Spacer(Modifier.width(sp.space4))
        Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(timestamp))
