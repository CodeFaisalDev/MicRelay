package com.micrelay.core.transport

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Resilient Dual UDP/TCP Transport for MicRelay.
 * Resolves addresses on background threads to prevent NetworkOnMainThreadException,
 * supports TCP for ADB USB forwarding, and performs Handshake ACKs.
 */
class UdpTransport : TransportInterface {
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var tcpOutputStream: OutputStream? = null

    private var targetAddress: InetAddress? = null
    private var targetHost: String = ""
    private var targetPort: Int = 45454

    private val isConnectedFlag = AtomicBoolean(false)
    private val isAcknowledgedFlag = AtomicBoolean(false)
    private var useTcp = false

    private val seqCounter = AtomicInteger(0)
    private val packetsSent = AtomicLong(0)

    private val sendBuffer = ByteBuffer.allocateDirect(2048)
    private val sendLock = Any()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var receiveThread: Thread? = null

    override var onConnected: ((String) -> Unit)? = null

    override fun connect(targetHost: String, targetPort: Int): Boolean {
        disconnect()
        this.targetHost = targetHost
        this.targetPort = targetPort
        this.useTcp = (targetHost == "127.0.0.1" || targetHost == "localhost")

        // Perform DNS resolution and socket connection on background executor
        networkExecutor.execute {
            try {
                this.targetAddress = InetAddress.getByName(targetHost)

                if (useTcp) {
                    // TCP mode (ideal for ADB USB port forwarding)
                    val sock = Socket(this.targetAddress, targetPort)
                    sock.tcpNoDelay = true
                    sock.sendBufferSize = 64 * 1024
                    this.tcpSocket = sock
                    this.tcpOutputStream = sock.getOutputStream()
                    this.isConnectedFlag.set(true)
                    Log.i("UdpTransport", "Connected via TCP to $targetHost:$targetPort")
                    startTcpReceiveLoop(sock)
                } else {
                    // UDP mode (ultra-low latency for WiFi)
                    val sock = DatagramSocket()
                    sock.sendBufferSize = 128 * 1024
                    this.udpSocket = sock
                    this.isConnectedFlag.set(true)
                    Log.i("UdpTransport", "Initialized UDP socket for $targetHost:$targetPort")
                    startUdpReceiveLoop(sock)
                }

                // Start periodic handshake ping loop until acknowledged
                startHandshakePingLoop()
            } catch (e: Exception) {
                Log.e("UdpTransport", "Connection error: ${e.message}", e)
                isConnectedFlag.set(false)
            }
        }
        return true
    }

    private fun startHandshakePingLoop() {
        Thread({
            var attempts = 0
            while (isConnectedFlag.get() && !isAcknowledgedFlag.get() && attempts < 15) {
                sendHandshake()
                attempts++
                try {
                    Thread.sleep(800)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }, "MicRelay-HandshakeLoop").apply { start() }
    }


    private fun startUdpReceiveLoop(sock: DatagramSocket) {
        receiveThread = Thread({
            val recvBuffer = ByteArray(1024)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)
            while (isConnectedFlag.get() && !sock.isClosed) {
                try {
                    sock.receive(packet)
                    if (packet.length >= WireProtocol.HEADER_SIZE) {
                        val magic0 = recvBuffer[0]
                        val magic1 = recvBuffer[1]
                        val payloadType = recvBuffer[3]
                        if (magic0 == WireProtocol.MAGIC[0] && magic1 == WireProtocol.MAGIC[1]) {
                            if (payloadType == WireProtocol.PAYLOAD_TYPE_ACK) {
                                Log.i("UdpTransport", "Received Handshake ACK from PC!")
                                isAcknowledgedFlag.set(true)
                                onConnected?.invoke("$targetHost:$targetPort")
                            }
                        }
                    }
                } catch (ignored: Exception) {
                    break
                }
            }
        }, "MicRelay-UdpRecvThread").apply { start() }
    }

    private fun startTcpReceiveLoop(sock: Socket) {
        receiveThread = Thread({
            try {
                val input: InputStream = sock.getInputStream()
                val recvBuffer = ByteArray(1024)
                while (isConnectedFlag.get() && !sock.isClosed) {
                    val read = input.read(recvBuffer)
                    if (read < 0) break
                    if (read >= WireProtocol.HEADER_SIZE) {
                        val payloadType = recvBuffer[3]
                        if (payloadType == WireProtocol.PAYLOAD_TYPE_ACK) {
                            Log.i("UdpTransport", "Received Handshake ACK from PC over TCP!")
                            isAcknowledgedFlag.set(true)
                            onConnected?.invoke("$targetHost:$targetPort")
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }, "MicRelay-TcpRecvThread").apply { start() }
    }

    override fun sendHandshake(): Boolean {
        val empty = ByteArray(0)
        return sendAudioFrame(WireProtocol.PAYLOAD_TYPE_HANDSHAKE, empty, 0, 0L)
    }

    override fun sendAudioFrame(
        payloadType: Byte,
        data: ByteArray,
        count: Int,
        timestampSamples: Long
    ): Boolean {
        if (!isConnectedFlag.get()) return false

        val seq = seqCounter.getAndIncrement()
        synchronized(sendLock) {
            try {
                WireProtocol.pack(
                    seq = seq,
                    timestampSamples = timestampSamples,
                    payloadType = payloadType,
                    data = data,
                    offset = 0,
                    length = count,
                    targetBuffer = sendBuffer
                )

                val packetLength = sendBuffer.remaining()
                val packetBytes = ByteArray(packetLength)
                sendBuffer.get(packetBytes)

                if (useTcp) {
                    tcpOutputStream?.write(packetBytes)
                    tcpOutputStream?.flush()
                } else {
                    val datagram = DatagramPacket(packetBytes, packetLength, targetAddress, targetPort)
                    udpSocket?.send(datagram)
                }

                packetsSent.incrementAndGet()
                return true
            } catch (e: Exception) {
                Log.e("UdpTransport", "Send error: ${e.message}")
                return false
            }
        }
    }

    override fun disconnect() {
        isConnectedFlag.set(false)
        isAcknowledgedFlag.set(false)
        try {
            udpSocket?.close()
        } catch (ignored: Exception) {}
        udpSocket = null

        try {
            tcpSocket?.close()
        } catch (ignored: Exception) {}
        tcpSocket = null
        tcpOutputStream = null

        targetAddress = null
    }

    override fun isConnected(): Boolean = isConnectedFlag.get()

    override fun isAcknowledged(): Boolean = isAcknowledgedFlag.get()

    override fun getPacketsSent(): Long = packetsSent.get()
}
