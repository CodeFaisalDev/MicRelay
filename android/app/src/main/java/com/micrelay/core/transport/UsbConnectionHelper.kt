package com.micrelay.core.transport

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Helper utility for detecting USB Tethering network interfaces and launching settings.
 */
object UsbConnectionHelper {

    /**
     * Checks if a USB tethering interface (e.g., rndis0, usb0, ncm0) is active and has an IPv4 address.
     */
    fun getUsbTetherIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (name.contains("rndis") || name.contains("usb") || name.contains("ncm")) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Launches Android's native Tethering settings screen so the user can easily toggle USB Tethering on.
     */
    fun openTetheringSettings(context: Context) {
        try {
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general wireless settings
            try {
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (ignored: Exception) {}
        }
    }
}
