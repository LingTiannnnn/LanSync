package com.lansync.app.ui.components

import android.net.Uri
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.documentfile.provider.DocumentFile
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class SaveDialogState {
    data class Saving(val fileName: String) : SaveDialogState()
    data class Completed(val fileName: String) : SaveDialogState()
    data class Error(val fileName: String, val message: String) : SaveDialogState()
}

/**
 * 将下载缓存文件复制到用户经 SAF 选择的目录（重名自动追加时间戳）。
 * 用户可见文案统一走字符串资源（[R.string]），无硬编码字面量。
 */
suspend fun copyFileToSafDirectory(
    context: Context,
    treeUri: Uri,
    fileName: String
): SaveDialogState = withContext(Dispatchers.IO) {
    try {
        val downloadsDir = File(context.cacheDir, "downloads")
        val sourceFile = File(downloadsDir, fileName)

        if (!sourceFile.exists()) {
            return@withContext SaveDialogState.Error(fileName, context.getString(R.string.save_err_source_missing))
        }

        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext SaveDialogState.Error(fileName, context.getString(R.string.save_err_dir_inaccessible))

        val mimeType = when {
            fileName.endsWith(".apks") -> "application/zip"
            fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }

        val existingFiles = documentFile.listFiles()
        val targetName = if (existingFiles.any { it.name == fileName }) {
            val base = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "")
            "${base}_${System.currentTimeMillis()}.$ext"
        } else {
            fileName
        }

        val createdFile = documentFile.createFile(mimeType, targetName)
            ?: return@withContext SaveDialogState.Error(fileName, context.getString(R.string.save_err_create_failed))

        context.contentResolver.openOutputStream(createdFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        } ?: return@withContext SaveDialogState.Error(fileName, context.getString(R.string.save_err_write_failed))

        SaveDialogState.Completed(fileName)
    } catch (e: SecurityException) {
        SaveDialogState.Error(fileName, context.getString(R.string.save_err_permission, e.message ?: ""))
    } catch (e: java.io.IOException) {
        if (e.message?.contains("No space") == true || e.message?.contains("ENOSPC") == true) {
            SaveDialogState.Error(fileName, context.getString(R.string.save_err_no_space))
        } else {
            SaveDialogState.Error(fileName, context.getString(R.string.save_err_generic, e.message ?: ""))
        }
    } catch (e: Exception) {
        SaveDialogState.Error(fileName, context.getString(R.string.save_err_generic, e.message ?: ""))
    }
}

@Composable
fun SaveStatusDialog(
    state: SaveDialogState,
    onDismiss: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    AlertDialog(
        onDismissRequest = {
            if (state !is SaveDialogState.Saving) onDismiss()
        },
        icon = {
            Icon(
                imageVector = when (state) {
                    is SaveDialogState.Saving -> Icons.Default.Save
                    is SaveDialogState.Completed -> Icons.Default.CheckCircle
                    is SaveDialogState.Error -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when (state) {
                    is SaveDialogState.Saving -> MaterialTheme.colorScheme.primary
                    is SaveDialogState.Completed -> MaterialTheme.colorScheme.primary
                    is SaveDialogState.Error -> MaterialTheme.colorScheme.error
                }
            )
        },
        title = {
            Text(
                text = when (state) {
                    is SaveDialogState.Saving -> stringResource(R.string.save_state_saving)
                    is SaveDialogState.Completed -> stringResource(R.string.save_state_completed)
                    is SaveDialogState.Error -> stringResource(R.string.save_state_error)
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                Text(
                    text = when (state) {
                        is SaveDialogState.Saving -> state.fileName
                        is SaveDialogState.Completed -> state.fileName
                        is SaveDialogState.Error -> state.fileName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                when (state) {
                    is SaveDialogState.Saving -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(sp.space4))
                        Text(
                            text = stringResource(R.string.save_saving_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SaveDialogState.Completed -> {
                        Text(
                            text = stringResource(R.string.save_completed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SaveDialogState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(sp.radiusXs),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.message,
                                modifier = Modifier.padding(sp.space8),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is SaveDialogState.Completed -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
                is SaveDialogState.Error -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
                is SaveDialogState.Saving -> {}
            }
        }
    )
}
