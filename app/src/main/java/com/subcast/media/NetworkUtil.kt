package com.subcast.media

import java.net.NetworkInterface

/** Finds the device's site-local IPv4 (192.168.x / 10.x / 172.16-31.x) on the LAN. */
object NetworkUtil {

    fun lanIpAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress || a.isSiteLocalAddress.not()) continue
                val s = a.hostAddress ?: continue
                if (s.contains('.') && !s.contains(':')) return s   // IPv4 only
            }
        }
        return null
    }
}
