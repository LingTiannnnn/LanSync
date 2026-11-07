package com.lansync.app.data.localapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lansync.app.data.FileLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 系统安装变化感知（ARCH §3.3）——LanSync 拿不到「安装器完成」事件（`InstallationResult.Success`
 * 只代表安装器已拉起），以 PackageManager 系统广播为准：`PACKAGE_ADDED` / `PACKAGE_REPLACED` /
 * `PACKAGE_FULLY_REMOVED` 任一即发射，事件携带 packageName。同时覆盖 LanSync 发起与用户手动/商店安装。
 *
 * 消费方（LanSyncRepository）双路径：单包外科更新（[LocalAppRepository.updateSinglePackage]，即时）
 * + 防抖全量重扫兜底（`AppConfig.packageChangeRescanDebounceMs` 后 `scanLocalApps`）。
 * 全量重扫延迟预算：广播即时 + 防抖 10s + 扫描（500 应用约 70s）；外科路径秒级。
 *
 * Android 耦合，**非 JVM 单测目标**；重扫/缓存/通知逻辑复用有 JVM 覆盖的 [LocalAppRepository] 链路。
 */
/** 安装变化事件：[packageName]（无法解析时为空串）+ 广播到达时刻 [atMillis]（重扫兜底的积压吸收判定用）。 */
data class PackageChangeEvent(val packageName: String, val atMillis: Long)

class PackageChangeMonitor(private val context: Context) {

    private val _events = MutableSharedFlow<PackageChangeEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 安装变化事件流（防抖前；消费方负责外科更新/防抖重扫）。 */
    val events: SharedFlow<PackageChangeEvent> = _events.asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart
                    ?: intent?.getStringExtra(Intent.EXTRA_DATA_REMOVED).orEmpty()
                FileLogger.i(TAG, "package broadcast: ${intent?.action} pkg=$pkg")
                _events.tryEmit(PackageChangeEvent(pkg, System.currentTimeMillis()))
            }
        }
        receiver = r
        // ADDED/REPLACED 意图携带 `package:` data URI —— filter 不声明 scheme 就匹配失败（matchData 规则）；
        // FULLY_REMOVED 无 URI，须单独的无 data filter。同一 receiver 实例注册两次。
        val changeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val removeFilter = IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED) }
        ContextCompat.registerReceiver(context, r, changeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, r, removeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        FileLogger.i(TAG, "package change monitor started")
    }

    fun stop() {
        val r = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(r) }
            .onFailure { FileLogger.w(TAG, "unregister failed: ${it.message}") }
        FileLogger.i(TAG, "package change monitor stopped")
    }

    private companion object {
        const val TAG = "PackageChangeMonitor"
    }
}
