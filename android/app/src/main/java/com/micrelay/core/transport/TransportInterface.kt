package com.micrelay.core.transport

interface TransportInterface {
    fun connect(targetHost: String, targetPort: Int): Boolean
    fun sendAudioFrame(payloadType: Byte, data: ByteArray, count: Int, timestampSamples: Long): Boolean
    fun sendHandshake(): Boolean
    fun disconnect()
    fun isConnected(): Boolean
    fun isAcknowledged(): Boolean
    fun getPacketsSent(): Long
    var onConnected: ((String) -> Unit)?
}
