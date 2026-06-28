package com.smartchoice.echoshare.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** UDP port used for room discovery broadcasts */
    const val DISCOVERY_PORT = 45678

    /** TCP port for control messages (PLAY, PAUSE, SEEK, etc.) */
    const val CONTROL_PORT = 45679

    /** TCP port for raw audio data streaming */
    const val AUDIO_PORT = 45680

    /** How often the host broadcasts its presence (ms) */
    const val DISCOVERY_INTERVAL_MS = 2000L

    /** Periodic sync heartbeat interval (ms) */
    const val SYNC_INTERVAL_MS = 5000L

    /** Maximum allowed clients */
    const val MAX_CLIENTS = 10

    /** Socket read timeout for clients (ms) */
    const val SOCKET_TIMEOUT_MS = 15_000

    /** Audio chunk size in bytes sent per packet */
    const val AUDIO_CHUNK_SIZE = 8192

    /**
     * Returns the device's current IPv4 address on the Wi-Fi/hotspot network,
     * or null if not connected.
     */
    fun getLocalIpAddress(context: Context): String? {
        // Try WifiManager first (most reliable on Android 10+)
        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        // Fallback: iterate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Returns the broadcast address for the current Wi-Fi network
     * (e.g. 192.168.1.255 for a /24 subnet).
     */
    fun getBroadcastAddress(context: Context): String {
        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val broadcast = dhcpInfo.ipAddress and dhcpInfo.netmask or dhcpInfo.netmask.inv()
            return String.format(
                "%d.%d.%d.%d",
                broadcast and 0xff,
                broadcast shr 8 and 0xff,
                broadcast shr 16 and 0xff,
                broadcast shr 24 and 0xff
            )
        } catch (_: Exception) {}
        return "255.255.255.255"
    }

    /** Returns true if the device has an active Wi-Fi or hotspot connection */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
