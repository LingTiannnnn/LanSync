package com.lansync.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.documentfile.provider.DocumentFile
import com.lansync.app.R
import com.lansync.app.data.FileLogger
import com.lansync.app.ui.string.saveFailureText
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** SAF 保存失败类别（UI 侧本地类型，不上线）。 */
enum class SaveFailure {
    SOURCE_MISSING,
    DIR_INACCESSIBLE,
    CREATE_FAILED,
    WRITE_FAILED,
    PERMISSION_DENIED,
    NO_SPACE,
    UNKNOWN,
}

sealed class SaveDialogState {
    data class Saving(val fileName: String) : SaveDialogState()
    data class Completed(val fileName: String) : SaveDialogState()

    /**
     * [reason] 是上屏的唯一依据（经 `FailureText` 映射资源）；
     * [detail] 是异常原文，只进 `FileLogger`，渲染它会把英文堆栈式短句暴露给用户。
     */
    data class Error(
        val fileName: String,
        val reason: SaveFailure,
        val detail: String? = null,
    ) : SaveDialogState()
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
    fun fail(reason: SaveFailure, detail: String? = null): SaveDialogState.Error {
        FileLogger.w("SaveToSaf", "save failed for $fileName: $reason ${detail.orEmpty()}")
        return SaveDialogState.Error(fileName, reason, detail)
    }
    try {
        val downloadsDir = File(context.cacheDir, "downloads")
        val sourceFile = File(downloadsDir, fileName)

        if (!sourceFile.exists()) {
            return@withContext fail(SaveFailure.SOURCE_MISSING, sourceFile.absolutePath)
        }

        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext fail(SaveFailure.DIR_INACCESSIBLE, treeUri.toString())

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
            ?: return@withContext fail(SaveFailure.CREATE_FAILED, targetName)

        context.contentResolver.openOutputStream(createdFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        } ?: return@withContext fail(SaveFailure.WRITE_FAILED, createdFile.uri.toString())

        SaveDialogState.Completed(fileName)
    } catch (e: SecurityException) {
        fail(SaveFailure.PERMISSION_DENIED, e.message)
    } catch (e: java.io.IOException) {
        if (e.message?.contains("No space") == true || e.message?.contains("ENOSPC") == true) {
            fail(SaveFailure.NO_SPACE, e.message)
        } else {
            fail(SaveFailure.UNKNOWN, e.message)
        }
    } catch (e: Exception) {
        fail(SaveFailure.UNKNOWN, e.message)
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
                    is SaveDialogState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        },
        title = {
            Text(
                text = stringResource(
                    when (state) {
                        is SaveDialogState.Saving -> R.string.save_state_saving
                        is SaveDialogState.Completed -> R.string.save_state_completed
                        is SaveDialogState.Error -> R.string.save_state_error
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
                    text = when (state) {
                        is SaveDialogState.Saving -> state.fileName
                        is SaveDialogState.Completed -> state.fileName
                        is SaveDialogState.Error -> state.fileName
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                when (state) {
                    is SaveDialogState.Saving -> {
                        LanSyncProgressBar(modifier = Modifier.fillMaxWidth())
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
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = saveFailureText(state.reason),
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
                is SaveDialogState.Completed, is SaveDialogState.Error -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok))
                }
                is SaveDialogState.Saving -> {
                    LanSyncSpinner(size = sp.iconMd, strokeWidth = sp.strokeThin)
                    Spacer(Modifier.height(sp.space2))
                }
            }
        }
    )
}
