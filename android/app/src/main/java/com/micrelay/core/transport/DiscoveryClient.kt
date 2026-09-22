package com.micrelay.core.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

data class DiscoveredServer(
    val hostname: String,
    val ip: String,
    val port: Int
)

/**
 * Discovers MicRelay PC Receiver on the local network via UDP broadcast beacon.
 */
object DiscoveryClient {
    private const val TAG = "DiscoveryClient"
    private const val DISCOVERY_PORT = 45454
    private const val DISCOVERY_MAGIC = "MICRELAY_DISCOVER"

    suspend fun discoverPc(timeoutMs: Long = 2500): DiscoveredServer? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs.toInt()
            }

            val sendData = DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)

            // 1. Broadcast to standard subnet broadcast
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, DISCOVERY_PORT)
            socket.send(sendPacket)
            Log.i(TAG, "Sent discovery broadcast probe to 255.255.255.255:$DISCOVERY_PORT")

            // 2. Also broadcast to all active local interface broadcast addresses
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val bcast = interfaceAddress.broadcast
                        if (bcast != null) {
                            val p = DatagramPacket(sendData, sendData.size, bcast, DISCOVERY_PORT)
                            socket.send(p)
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 3. Listen for response
            val receiveBuf = ByteArray(2048)
            val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
            socket.receive(receivePacket)

            val reply = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8).trim()
            val responderIp = receivePacket.address.hostAddress ?: ""
            Log.i(TAG, "Received discovery reply: '$reply' from $responderIp")

            if (reply.startsWith("MICRELAY_BEACON:")) {
                val parts = reply.split(":")
                if (parts.size >= 4) {
                    val hostname = parts[1]
                    val pcIp = parts[2]
                    val port = parts[3].toIntOrNull() ?: DISCOVERY_PORT
                    return@withContext DiscoveredServer(hostname, pcIp, port)
                }
            } else if (reply.startsWith("{") && reply.contains("MicRelay")) {
                val json = JSONObject(reply)
                val hostname = json.optString("hostname", "PC")
                val pcIp = json.optString("ip", responderIp)
                val port = json.optInt("port", DISCOVERY_PORT)
                return@withContext DiscoveredServer(hostname, pcIp, port)
            }

            // Fallback: responder answered on port 45454
            return@withContext DiscoveredServer("PC-Receiver", responderIp, DISCOVERY_PORT)

        } catch (e: Exception) {
            Log.w(TAG, "Discovery scan timed out or failed: ${e.message}")
            return@withContext null
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Retrieves the current device's local Wi-Fi IP address for subnet verification.
     */
    fun getLocalWifiIp(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                return Formatter.formatIpAddress(ipAddress)
            }
        } catch (ignored: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}

        return null
    }
}
