package com.lansync.app.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

object FileLogger {

    private const val LOG_FILE_NAME = "lansync_debug.log"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024
    private const val MAX_BACKUP_COUNT = 3
    private var logDir: File? = null
    private var isInitialized = false

    private val logQueue = ConcurrentLinkedQueue<String>()
    private var writerThread: Thread? = null
    private var running = false

    fun init(context: Context) {
        if (isInitialized) return
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            logDir = externalDir
            rotateLogFiles()
            isInitialized = true
            startWriterThread()
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

    private fun startWriterThread() {
        if (running) return
        running = true
        writerThread = thread(name = "FileLogger-Writer", isDaemon = true) {
            while (running) {
                try {
                    val entry = logQueue.poll()
                    if (entry != null) {
                        writeToFile(entry)
                    } else {
                        Thread.sleep(50)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    break
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
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        logQueue.offer("$timestamp [$level] $tag: $message")
    }

    @Synchronized
    private fun writeToFile(entry: String) {
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

    fun getLogFilePath(): String? = logDir?.resolve(LOG_FILE_NAME)?.absolutePath

    fun clearLog() {
        val dir = logDir ?: return
        try {
            File(dir, LOG_FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        running = false
        writerThread?.interrupt()
        writerThread = null
        val remaining = StringBuilder()
        while (true) {
            val entry = logQueue.poll() ?: break
            remaining.append(entry).append("\n")
        }
        if (remaining.isNotEmpty()) {
            writeToFile(remaining.toString())
        }
    }
}
