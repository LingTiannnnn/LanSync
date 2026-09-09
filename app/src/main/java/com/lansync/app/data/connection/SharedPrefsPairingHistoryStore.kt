package com.lansync.app.data.connection

import android.content.Context

/**
 * [PairingHistoryStore] 的 SharedPreferences 持久化实现（SPEC §7.7 TT3）。
 *
 * 记录「历史配对成功过」的 `instanceId`，跨进程重启保留——这是反向连接自动接受的唯一判据。
 * 本机私有，不上线。键：每个 instanceId 一条布尔（`lansync_pairing` prefs）。
 */
class SharedPrefsPairingHistoryStore(context: Context) : PairingHistoryStore {

    private val prefs = context.applicationContext.getSharedPreferences("lansync_pairing", Context.MODE_PRIVATE)

    override fun isPaired(instanceId: String): Boolean =
        instanceId.isNotEmpty() && prefs.getBoolean(instanceId, false)

    override fun recordPaired(instanceId: String) {
        if (instanceId.isNotEmpty()) prefs.edit().putBoolean(instanceId, true).apply()
    }
}
