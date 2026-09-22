package com.micrelay.core.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * mDNS/NSD Advertiser for zero-configuration PC discovery.
 */
class NsdAdvertiser(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isAdvertising = false

    fun startAdvertising(port: Int = 45454, deviceName: String = Build.MODEL) {
        if (isAdvertising) return

        try {
            // Android drops multicast packets unless MulticastLock is held
            multicastLock = wifiManager.createMulticastLock("MicRelay_mDNS_Lock").apply {
                setReferenceCounted(true)
                acquire()
            }

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "MicRelay-$deviceName"
                serviceType = "_micrelay._udp."
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("dev", deviceName)
                    setAttribute("v", "1")
                }
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(service: NsdServiceInfo) {
                    isAdvertising = true
                }
                override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                    isAdvertising = false
                }
                override fun onServiceUnregistered(service: NsdServiceInfo) {
                    isAdvertising = false
                }
                override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                    isAdvertising = false
                }
            }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAdvertising() {
        if (!isAdvertising) return
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (ignored: Exception) {}
        registrationListener = null
        isAdvertising = false

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
        multicastLock = null
    }
}
