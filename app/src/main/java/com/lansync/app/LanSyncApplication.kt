package com.lansync.app

import android.app.Application
import com.lansync.app.data.FileLogger

class LanSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        installCrashLogger()
    }

    /** 把未捕获异常同步写入文件日志（FileLogger 默认异步缓冲，进程被杀会丢日志）。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e(
                    "CRASH",
                    "Uncaught on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}",
                    throwable,
                )
                FileLogger.flushSync()
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
