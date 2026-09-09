package com.lansync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lansync.app.MainActivity
import com.lansync.app.R
import com.lansync.app.data.FileLogger
import com.lansync.app.data.repository.LanSyncGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务（ARCH §8）——承载 [com.lansync.app.data.repository.LanSyncRepository] 的 start/stop 生命周期，
 * 脱离 Activity：退后台/进程被杀（START_STICKY）后「服务端 + 发现 + 心跳」仍存活。
 *
 * - `foregroundServiceType=specialUse`（API 34 强制声明 + `FOREGROUND_SERVICE_SPECIAL_USE` 权限，Manifest 已配）。
 * - 常驻通知显示「端口 / 已连接数」，含「停止同步」操作 + 点击打开 [MainActivity]。
 * - 生命周期归属：服务持有 repo.start/stop（不再由 ViewModel 直接持有），ViewModel 仅触发服务 + 观察 StateFlow。
 *
 * Android 生命周期耦合，**非 JVM 单测目标**（编译校验 + 真机验收：退后台存活、通知、进程被杀恢复）。
 */
class ForegroundSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSync()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startSync()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("正在启动同步服务…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startSync() {
        val repo = LanSyncGraph.get(applicationContext)
        serviceScope.launch {
            repo.start()
            observeJob?.cancel()
            observeJob = launch {
                combine(repo.serverPort, repo.connectedDevices) { port, connected -> port to connected.size }
                    .collectLatest { (port, count) ->
                        updateNotification("运行中 · 端口 $port · 已连接 $count 台")
                    }
            }
            FileLogger.i(TAG, "foreground sync started")
        }
    }

    private fun stopSync() {
        observeJob?.cancel()
        observeJob = null
        serviceScope.launch {
            LanSyncGraph.get(applicationContext).stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            FileLogger.i(TAG, "foreground sync stopped")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ForegroundSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止同步", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "同步服务", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val TAG = "ForegroundSyncService"
        const val ACTION_STOP = "com.lansync.app.action.STOP_SYNC"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "lansync_sync"

        /** 前台启动同步服务（须在用户前台交互时调用，规避 Android 12+ 后台启动 FGS 限制）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ForegroundSyncService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ForegroundSyncService::class.java).setAction(ACTION_STOP))
        }
    }
}
