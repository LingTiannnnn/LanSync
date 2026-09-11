package com.lansync.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getLocalIpAddressViaWifi(context: Context): InetAddress {
        // Android 10+ 上 connectionInfo.ipAddress 常因隐私限制返回 0（API 已废弃），
        // 优先走 NetworkInterface；仅旧系统或接口枚举失败时再试 WifiManager。
        getLocalIpAddress().takeIf { it.isNotBlank() }?.let { return InetAddress.getByName(it) }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            val ipAddress = Formatter.formatIpAddress(ipInt)
            return InetAddress.getByName(ipAddress)
        }
        FileLogger.i(TAG, "WiFi ipAddress is 0; network interface fallback used")
        return InetAddress.getByName("127.0.0.1")
    }

    private const val TAG = "NetworkUtils"
}