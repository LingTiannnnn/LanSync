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
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            val ipAddress = Formatter.formatIpAddress(ipInt)
            return InetAddress.getByName(ipAddress)
        }
        FileLogger.w(TAG, "WiFi ipAddress is 0, trying network interface fallback")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error finding fallback IP", e)
        }
        return InetAddress.getByName("127.0.0.1")
    }

    fun getLocalDisplayKey(context: Context, port: Int = 0): String {
        val ipAddress = getLocalIpAddress()
        return if (ipAddress.isNotEmpty()) {
            "$ipAddress:$port"
        } else {
            ""
        }
    }

    private const val TAG = "NetworkUtils"
}