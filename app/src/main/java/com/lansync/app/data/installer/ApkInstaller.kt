package com.lansync.app.data.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.lansync.app.data.FileLogger
import java.io.File

class ApkInstaller(private val context: Context) {

    fun installApks(file: File): InstallationResult {
        FileLogger.i("ApkInstaller", "installApks called: ${file.absolutePath} (exists=${file.exists()}, size=${file.length()})")

        return try {
            if (!file.exists()) {
                FileLogger.e("ApkInstaller", "installApks: file not found! ${file.absolutePath}")
                return InstallationResult.Error("File not found")
            }

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
                FileLogger.e("ApkInstaller", "installApks: No package installer available!")
                InstallationResult.Error("No package installer available")
            }
        } catch (e: Exception) {
            FileLogger.e("ApkInstaller", "installApks EXCEPTION: ${e.message}", e)
            InstallationResult.Error(e.message ?: "Unknown error")
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

    fun canHandleInstall(file: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun getInstallUri(file: File): Uri? {
        return try {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            null
        }
    }

    sealed class InstallationResult {
        object Success : InstallationResult()
        data class Error(val message: String) : InstallationResult()
    }
}
