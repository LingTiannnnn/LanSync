package com.lansync.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {

    private const val LOG_FILE_NAME = "lansync_debug.log"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024
    private const val MAX_BACKUP_COUNT = 3
    private var logDir: File? = null
    private var isInitialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(UNLIMITED)
    private val writeMutex = Mutex()
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        if (isInitialized) return
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            logDir = externalDir
            rotateLogFiles()
            isInitialized = true
            startConsumerJob()
            i("FileLogger", "Log directory: ${logDir?.absolutePath}")
        }
    }

    private fun rotateLogFiles() {
        val dir = logDir ?: return
        try {
            val currentLog = File(dir, LOG_FILE_NAME)

            if (currentLog.exists() && currentLog.length() > 0) {
                shiftBackups(dir)
                val backup = File(dir, "${LOG_FILE_NAME}.bak1")
                backup.delete()
                currentLog.renameTo(backup)
            }

            currentLog.createNewFile()
        } catch (_: Exception) {
        }
    }

    private fun shiftBackups(dir: File) {
        for (i in MAX_BACKUP_COUNT downTo 1) {
            val bakFile = File(dir, "${LOG_FILE_NAME}.bak$i")
            if (bakFile.exists()) {
                if (i == MAX_BACKUP_COUNT) {
                    bakFile.delete()
                } else {
                    val nextBak = File(dir, "${LOG_FILE_NAME}.bak${i + 1}")
                    nextBak.delete()
                    bakFile.renameTo(nextBak)
                }
            }
        }
    }

    private fun startConsumerJob() {
        scope.launch {
            for (entry in logChannel) {
                if (!isActive) break
                try {
                    writeToFile(entry)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun d(tag: String, message: String) {
        enqueue("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        enqueue("INFO ", tag, message)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        enqueue("WARN ", tag, message + if (t != null) "\n  ${t.message}\n  ${t.stackTraceToString().lines().take(8).joinToString("\n  ")}" else "")
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        enqueue("ERROR", tag, message + if (t != null) "\n  ${t.message}\n  ${t.stackTraceToString().lines().take(8).joinToString("\n  ")}" else "")
    }

    fun json(tag: String, label: String, data: Any?) {
        val content = when (data) {
            null -> "null"
            is String -> if (data.length > 500) data.take(500) + "...[truncated]" else data
            else -> data.toString().take(500)
        }
        enqueue("JSON  ", tag, "[$label] $content")
    }

    private fun enqueue(level: String, tag: String, message: String) {
        if (!isInitialized) return
        val timestamp = dateFormat.get()!!.format(Date())
        logChannel.trySend("$timestamp [$level] $tag: $message")
    }

    private suspend fun writeToFile(entry: String) {
        writeMutex.withLock {
            val dir = logDir ?: return
            try {
                val logFile = File(dir, LOG_FILE_NAME)
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                    val backup = File(dir, "lansync_debug.prev.log")
                    backup.delete()
                    logFile.renameTo(backup)
                }
                FileWriter(logFile, true).use { writer ->
                    writer.append(entry)
                    writer.append("\n")
                    writer.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun getLogFilePath(): String? = logDir?.resolve(LOG_FILE_NAME)?.absolutePath

    fun clearLog() {
        val dir = logDir ?: return
        try {
            File(dir, LOG_FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        val remaining = mutableListOf<String>()
        while (true) {
            val entry = logChannel.tryReceive().getOrNull() ?: break
            remaining.add(entry)
        }
        logChannel.close()

        if (remaining.isNotEmpty()) {
            scope.launch {
                for (entry in remaining) {
                    writeToFile(entry)
                }
            }
        }
    }
}