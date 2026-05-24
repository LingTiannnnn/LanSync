package com.lansync.app.data

data class AppConfig(
    val heartbeatPingIntervalMs: Long = 20_000L,
    val heartbeatSyncIntervalMs: Long = 120_000L,
    val heartbeatPingTolerance: Int = 1,
    val heartbeatPingMaxFailures: Int = 4,
    val updateRecalculationThrottleMs: Long = 5_000L,
    val connectTimeoutMs: Long = 30_000L,
    val requestTimeoutMs: Long = 15_000L,
    val pollIntervalMs: Long = 500L,
    val downloadBufferSize: Int = 65536,
    val sendBufferSize: Int = 65536,
    val fetchAppListMaxRetries: Int = 5,
    val fetchAppListRetryDelayMs: Long = 3_000L,
    val pingTimeoutMs: Long = 3_000L
) {
    companion object {
        val DEFAULT = AppConfig()
    }
}