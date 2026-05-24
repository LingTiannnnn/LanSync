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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class SaveDialogState {
    data class Saving(val fileName: String) : SaveDialogState()
    data class Completed(val fileName: String) : SaveDialogState()
    data class Error(val fileName: String, val message: String) : SaveDialogState()
}

suspend fun copyFileToSafDirectory(
    context: Context,
    treeUri: Uri,
    fileName: String
): SaveDialogState = withContext(Dispatchers.IO) {
    try {
        val downloadsDir = File(context.cacheDir, "downloads")
        val sourceFile = File(downloadsDir, fileName)

        if (!sourceFile.exists()) {
            return@withContext SaveDialogState.Error(fileName, "源文件不存在")
        }

        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext SaveDialogState.Error(fileName, "无法访问选择的目录")

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
            ?: return@withContext SaveDialogState.Error(fileName, "无法在目标目录创建文件")

        context.contentResolver.openOutputStream(createdFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        } ?: return@withContext SaveDialogState.Error(fileName, "无法写入文件")

        SaveDialogState.Completed(fileName)
    } catch (e: SecurityException) {
        SaveDialogState.Error(fileName, "权限不足：${e.message}")
    } catch (e: java.io.IOException) {
        if (e.message?.contains("No space") == true || e.message?.contains("ENOSPC") == true) {
            SaveDialogState.Error(fileName, "存储空间不足，请清理后重试")
        } else {
            SaveDialogState.Error(fileName, "保存失败：${e.message}")
        }
    } catch (e: Exception) {
        SaveDialogState.Error(fileName, "保存失败：${e.message}")
    }
}

@Composable
fun SaveStatusDialog(
    state: SaveDialogState,
    onDismiss: () -> Unit
) {
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
                    is SaveDialogState.Saving -> "保存中"
                    is SaveDialogState.Completed -> "保存完成"
                    is SaveDialogState.Error -> "保存失败"
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "正在保存到选择的目录...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SaveDialogState.Completed -> {
                        Text(
                            text = "文件已成功保存到目标目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is SaveDialogState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.message,
                                modifier = Modifier.padding(8.dp),
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
                        Text("确定")
                    }
                }
                is SaveDialogState.Error -> {
                    TextButton(onClick = onDismiss) {
                        Text("确定")
                    }
                }
                is SaveDialogState.Saving -> {}
            }
        }
    )
}