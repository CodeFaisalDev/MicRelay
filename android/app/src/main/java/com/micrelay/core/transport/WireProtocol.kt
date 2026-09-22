package com.micrelay.core.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary Packet Formatter for MicRelay 18-byte Wire Protocol.
 */
object WireProtocol {
    val MAGIC = byteArrayOf(0x4D.toByte(), 0x52.toByte()) // 'M', 'R'
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 18

    const val PAYLOAD_TYPE_HEARTBEAT: Byte = 0
    const val PAYLOAD_TYPE_OPUS: Byte = 1
    const val PAYLOAD_TYPE_PCM: Byte = 2
    const val PAYLOAD_TYPE_HANDSHAKE: Byte = 3
    const val PAYLOAD_TYPE_ACK: Byte = 4
    const val PAYLOAD_TYPE_DISCOVERY: Byte = 5
    const val PAYLOAD_TYPE_DISCOVERY_ACK: Byte = 6

    /**
     * Packs audio payload with the 18-byte MicRelay header into a direct ByteBuffer.
     */
    fun pack(
        seq: Int,
        timestampSamples: Long,
        payloadType: Byte,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        targetBuffer: ByteBuffer
    ) {
        targetBuffer.clear()
        targetBuffer.order(ByteOrder.BIG_ENDIAN)
        
        targetBuffer.put(MAGIC)
        targetBuffer.put(VERSION)
        targetBuffer.put(payloadType)
        targetBuffer.putInt(seq)
        targetBuffer.putLong(timestampSamples)
        targetBuffer.putShort(length.toShort())
        if (length > 0) {
            targetBuffer.put(data, offset, length)
        }
        
        targetBuffer.flip()
    }
}
