package com.micrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.micrelay.R
import com.micrelay.core.audio.*
import com.micrelay.core.transport.NsdAdvertiser
import com.micrelay.core.transport.TransportInterface
import com.micrelay.core.transport.UdpTransport
import com.micrelay.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class RelayMode {
    AUDIO_ONLY,
    VIDEO_ONLY,
    BOTH
}

data class ServiceState(
    val isRunning: Boolean = false,
    val isConnectedToPc: Boolean = false,
    val mode: RelayMode = RelayMode.AUDIO_ONLY,
    val targetHost: String = "192.168.10.116",
    val targetPort: Int = 45454,
    val vuDecibels: Float = -60.0f,
    val packetsSent: Long = 0L,
    val isAdvertising: Boolean = true,
    val activeMicName: String = "🎙️ Phone Studio Mic Array",
    val isExternalMic: Boolean = false,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.VOICE_RECOGNITION,
    val isNoiseSuppressionEnabled: Boolean = true
)

class MicRelayService : Service() {

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(ServiceState())
    val state = _state.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private val ringBuffer = AudioRingBuffer()
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var opusEncoder: OpusAudioEncoder
    private lateinit var aacEncoder: AacAudioEncoder
    private val transport: TransportInterface = UdpTransport()
    private lateinit var nsdAdvertiser: NsdAdvertiser

    var onError: ((String) -> Unit)? = null

    private val isStreaming = AtomicBoolean(false)
    private var networkWorkerThread: Thread? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): MicRelayService = this@MicRelayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        nsdAdvertiser = NsdAdvertiser(this)
        opusEncoder = OpusAudioEncoder()
        audioCaptureManager = AudioCaptureManager(ringBuffer = ringBuffer, context = this)
        aacEncoder = AacAudioEncoder(ringBuffer = ringBuffer)

        audioCaptureManager.onError = { err ->
            onError?.invoke(err)
        }

        audioCaptureManager.onExternalMicPlugged = { dev ->
            _state.value = _state.value.copy(
                activeMicName = dev.name,
                isExternalMic = dev.isExternal
            )
        }

        audioCaptureManager.onDevicesUpdated = { _ ->
            val current = audioCaptureManager.selectedDevice
            _state.value = _state.value.copy(
                activeMicName = current?.name ?: "🎙️ Phone Studio Mic Array",
                isExternalMic = current?.isExternal ?: false
            )
        }

        transport.onConnected = { endpoint ->
            _state.value = _state.value.copy(isConnectedToPc = true)
        }

        audioCaptureManager.onDecibelUpdate = { db ->
            _state.value = _state.value.copy(
                vuDecibels = db,
                packetsSent = transport.getPacketsSent()
            )
        }

        createNotificationChannel()
    }

    fun getAvailableInputDevices(): List<AudioInputDevice> {
        return audioCaptureManager.getAvailableInputDevices()
    }

    fun setPreferredMicDevice(device: AudioInputDevice?) {
        audioCaptureManager.setPreferredMicDevice(device)
        _state.value = _state.value.copy(
            activeMicName = device?.name ?: "🎙️ Phone Studio Mic Array",
            isExternalMic = device?.isExternal ?: false
        )
    }

    fun setAudioSourceMode(mode: AudioSourceMode) {
        audioCaptureManager.audioSourceMode = mode
        _state.value = _state.value.copy(audioSourceMode = mode)
    }

    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        audioCaptureManager.isNoiseSuppressionEnabled = enabled
        _state.value = _state.value.copy(isNoiseSuppressionEnabled = enabled)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MicRelay Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing status of phone mic streaming"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun startRelay(mode: RelayMode, targetHost: String, targetPort: Int) {
        if (isStreaming.get()) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MicRelay::StreamingWakeLock").apply {
            acquire(4 * 60 * 60 * 1000L)
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = if (mode == RelayMode.AUDIO_ONLY) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (mode == RelayMode.AUDIO_ONLY || mode == RelayMode.BOTH) {
            transport.connect(targetHost, targetPort)
        }

        audioCaptureManager.start()
        isStreaming.set(true)

        if (mode == RelayMode.AUDIO_ONLY || mode == RelayMode.BOTH) {
            networkWorkerThread = Thread({
                networkStreamLoop()
            }, "MicRelay-NetworkStreamerThread").apply {
                start()
            }
        }

        nsdAdvertiser.startAdvertising(targetPort)

        _state.value = _state.value.copy(
            isRunning = true,
            isConnectedToPc = false,
            mode = mode,
            targetHost = targetHost,
            targetPort = targetPort
        )
    }

    private fun networkStreamLoop() {
        val readBuffer = ShortArray(480)
        val pcmBytes = ByteArray(480 * 2)
        val byteBuffer = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var cursor = ringBuffer.getHead()

        while (isStreaming.get()) {
            val (readCount, newCursor) = ringBuffer.read(cursor, readBuffer, readBuffer.size)
            if (readCount > 0) {
                cursor = newCursor
                byteBuffer.clear()
                val shortBuffer = byteBuffer.asShortBuffer()
                shortBuffer.put(readBuffer, 0, readCount)
                transport.sendAudioFrame(
                    payloadType = com.micrelay.core.transport.WireProtocol.PAYLOAD_TYPE_PCM,
                    data = pcmBytes,
                    count = readCount * 2,
                    timestampSamples = cursor
                )
            } else {
                try {
                    Thread.sleep(3)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
    }

    fun startLocalAudioRecord(outputM4aFile: java.io.File): Boolean {
        return aacEncoder.startRecording(outputM4aFile)
    }

    fun stopLocalAudioRecord() {
        aacEncoder.stopRecording()
    }

    fun stopRelay() {
        isStreaming.set(false)
        try {
            networkWorkerThread?.join(500)
        } catch (ignored: Exception) {}
        networkWorkerThread = null

        stopLocalAudioRecord()
        audioCaptureManager.stop()
        transport.disconnect()
        nsdAdvertiser.stopAdvertising()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        _state.value = _state.value.copy(isRunning = false, isConnectedToPc = false)
    }

    fun getRingBuffer(): AudioRingBuffer = ringBuffer

    override fun onDestroy() {
        stopRelay()
        audioCaptureManager.release()
        opusEncoder.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "micrelay_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
