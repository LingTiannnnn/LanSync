package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.transfer.LanSyncClient
import com.lansync.app.ui.theme.LanSyncTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间戳显示格式（SimpleDateFormat 技术格式，非 UI 文案，故不入 strings.xml）。 */
private const val TIME_PATTERN = "MM-dd HH:mm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    files: List<LanSyncClient.DownloadedFileInfo>,
    onInstall: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    val selectedFiles = remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (files.isNotEmpty()) {
            val allSelected = selectedFiles.value.size == files.size && files.isNotEmpty()
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sp.space16, vertical = sp.space12),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedFiles.value.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.selection_count_files, selectedFiles.value.size),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = selectedFiles.value.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(sp.iconMd))
                        Spacer(Modifier.width(sp.space4))
                        Text(stringResource(R.string.action_delete))
                    }
                    TextButton(onClick = {
                        selectedFiles.value = if (allSelected) {
                            emptySet()
                        } else {
                            files.map { it.fileName }.toSet()
                        }
                    }) {
                        Icon(
                            imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = null,
                            modifier = Modifier.size(sp.iconMd)
                        )
                        Spacer(Modifier.width(sp.space4))
                        Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all))
                    }
                    TextButton(onClick = { selectedFiles.value = emptySet() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = stringResource(R.string.files_empty_title),
                    body = stringResource(R.string.files_empty_hint),
                )
            }
        } else {
            val totalBytes = files.sumOf { it.fileSize }
            SummaryCard(
                primary = stringResource(R.string.files_summary_primary, files.size),
                secondary = stringResource(
                    R.string.files_summary_secondary,
                    formatFileSize(totalBytes),
                ),
                modifier = Modifier.padding(horizontal = sp.space16, vertical = sp.space8),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = sp.space8),
                verticalArrangement = Arrangement.spacedBy(sp.space6)
            ) {
                items(files, key = { it.fileName }) { file ->
                    DownloadedFileItem(
                        file = file,
                        isSelected = file.fileName in selectedFiles.value,
                        onSelect = {
                            selectedFiles.value = if (file.fileName in selectedFiles.value) {
                                selectedFiles.value - file.fileName
                            } else {
                                selectedFiles.value + file.fileName
                            }
                        },
                        onSave = { onSave(file.fileName) },
                        onInstall = { onInstall(file.fileName) }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.files_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.files_delete_confirm_message, selectedFiles.value.size))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(selectedFiles.value.toList())
                        selectedFiles.value = emptySet()
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
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onInstall: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.space12),
        shape = RoundedCornerShape(sp.radiusMd),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.space12)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.padding(end = sp.space8),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )

                AppIcon(
                    packageName = file.packageName,
                    size = sp.appIconSm
                )

                Spacer(Modifier.width(sp.space10))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.packageName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (file.isSplitApk) {
                            Spacer(Modifier.width(sp.space6))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(sp.radiusXs)
                            ) {
                                Text(
                                    text = stringResource(R.string.badge_split),
                                    modifier = Modifier.padding(horizontal = sp.space4, vertical = sp.hairline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(sp.space2))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(sp.space8)
                    ) {
                        Text(
                            text = formatFileSize(file.fileSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.file_meta_separator),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTime(file.lastModified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(sp.space8))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.height(sp.buttonHeight),
                    contentPadding = PaddingValues(horizontal = sp.space12)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(sp.iconSm))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.width(sp.space8))

                FilledTonalButton(
                    onClick = onInstall,
                    modifier = Modifier.height(sp.buttonHeight),
                    contentPadding = PaddingValues(horizontal = sp.space12)
                ) {
                    Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(sp.iconSm))
                    Spacer(Modifier.width(sp.space4))
                    Text(stringResource(R.string.action_install), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> stringResource(R.string.size_bytes, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.size_kb, bytes / 1024.0)
        else -> stringResource(R.string.size_mb_decimal, bytes / (1024.0 * 1024.0))
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(timestamp))
}
