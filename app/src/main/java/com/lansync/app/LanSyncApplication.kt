package com.lansync.app

import android.app.Application
import com.lansync.app.data.FileLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LanSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        logInstallFingerprint()
        installCrashLogger()
        logClassProbe()
    }

    /**
     * 关键类探针：排查「装的包是否残缺」时以日志首屏 probe 行为准。用直接类引用而非
     * Class.forName(原名)——R8 混淆后会重写类字面量，按原名反射查不到已混淆的类。
     */
    private fun logClassProbe() {
        probe("ktor-application-engine") { io.ktor.server.engine.ApplicationEngine::class.java }
        probe("ktor-cio-engine") { io.ktor.server.cio.CIOApplicationEngine::class.java }
        probe("ktor-network-socket") { io.ktor.network.sockets.ServerSocket::class.java }
        probe("kotlinx-json") { kotlinx.serialization.json.Json::class.java }
        probe("jmdns") { javax.jmdns.JmDNS::class.java }
    }

    private fun probe(label: String, resolve: () -> Class<*>) {
        val status = try {
            resolve()
            "ok"
        } catch (e: Throwable) {
            "MISSING(${e.javaClass.simpleName})"
        }
        FileLogger.i("App", "class probe: $label -> $status")
    }

    /** 安装指纹：版本号 + APK 最近安装时间。排查「装机的是不是最新包」时以日志首屏这行为准。 */
    private fun logInstallFingerprint() {
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val updated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.lastUpdateTime))
            FileLogger.i("App", "build ${info.versionName} (${info.versionCode}), apk installed/updated at $updated")
        } catch (e: Exception) {
            FileLogger.w("App", "install fingerprint unavailable: ${e.javaClass.simpleName}")
        }
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
