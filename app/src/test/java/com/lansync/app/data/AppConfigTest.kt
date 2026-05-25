package com.lansync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {

    @Test
    fun `default config has expected values`() {
        val config = AppConfig.DEFAULT
        assertEquals(20_000L, config.heartbeatPingIntervalMs)
        assertEquals(120_000L, config.heartbeatSyncIntervalMs)
        assertEquals(1, config.heartbeatPingTolerance)
        assertEquals(4, config.heartbeatPingMaxFailures)
        assertEquals(5_000L, config.updateRecalculationThrottleMs)
        assertEquals(30_000L, config.connectTimeoutMs)
        assertEquals(500L, config.pollIntervalMs)
    }

    @Test
    fun `custom config overrides values`() {
        val config = AppConfig(heartbeatPingIntervalMs = 10_000L)
        assertEquals(10_000L, config.heartbeatPingIntervalMs)
        assertEquals(120_000L, config.heartbeatSyncIntervalMs)
    }
}