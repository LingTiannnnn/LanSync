package com.lansync.app.data.installer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lansync.app.data.FileLogger
import com.lansync.app.data.model.InstallFailure
import java.io.File

class ApkInstaller(private val context: Context) {

    fun installApks(file: File): InstallationResult {
        FileLogger.i("ApkInstaller", "installApks called: ${file.absolutePath} (exists=${file.exists()}, size=${file.length()})")

        if (!file.exists()) {
            FileLogger.e("ApkInstaller", "installApks: file not found! ${file.absolutePath}")
            return InstallationResult.Error(InstallFailure.FILE_MISSING, "missing ${file.absolutePath}")
        }

        return try {

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            FileLogger.d("ApkInstaller", "installApks: URI=$uri, mimeType=${getMimeType(file)}")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resInfo = context.packageManager.queryIntentActivities(intent, 0)
            if (resInfo.isNotEmpty()) {
                context.startActivity(intent)
                FileLogger.i("ApkInstaller", "installApks SUCCESS: launched installer for ${file.name}")
                InstallationResult.Success
            } else {
                FileLogger.e("ApkInstaller", "installApks: no package installer for ${file.name}")
                InstallationResult.Error(InstallFailure.INSTALLER_UNAVAILABLE, "no resolver for ${getMimeType(file)}")
            }
        } catch (e: Exception) {
            FileLogger.e("ApkInstaller", "installApks EXCEPTION: ${e.message}", e)
            InstallationResult.Error(InstallFailure.INSTALLER_EXCEPTION, e.message)
        }
    }

    private fun getMimeType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".apks") -> "application/zip"
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    sealed class InstallationResult {
        object Success : InstallationResult()

        /** 用户在下载中主动取消：不是失败，UI 不按错误处理，也不进失败文案映射。 */
        object Cancelled : InstallationResult()

        /**
         * [failure] 是 UI 唯一可渲染的信息（经 `ui/string/FailureText.kt` 映射资源）；
         * [message] 只作诊断，进 `FileLogger`，不得上屏（ARCH §7 / CONVENTIONS §3）。
         */
        data class Error(val failure: InstallFailure, val message: String? = null) : InstallationResult()
    }
}
