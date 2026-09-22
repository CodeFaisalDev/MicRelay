package com.micrelay.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max

enum class AudioSourceMode(val id: Int, val displayName: String, val audioSource: Int) {
    VOICE_RECOGNITION(0, "🎙️ Voice Recognition (WO Mic Mode)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC(1, "📻 Studio Raw (Pure ADC)", MediaRecorder.AudioSource.MIC),
    CAMCORDER(2, "📹 Camcorder Wideband", MediaRecorder.AudioSource.CAMCORDER),
    VOICE_COMMUNICATION(3, "🎧 Voice Communication (AEC)", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
}

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val isExternal: Boolean,
    val deviceInfo: AudioDeviceInfo?
)

/**
 * Manages low-latency studio microphone capture via AudioRecord.
 * Features:
 * - Pure raw microphone recording without aggressive DSP noise suppression or gain muffling
 * - Automatic Bluetooth SCO activation & routing for wireless headsets (AirPods, Galaxy Buds, etc.)
 * - Dynamic external microphone routing (K9 wireless USB-C, 3.5mm lavalier, Bluetooth)
 * - Hot-plug detection via AudioDeviceCallback
 * - Real-time peak level measurement
 */
class AudioCaptureManager(
    val sampleRate: Int = 48000,
    val ringBuffer: AudioRingBuffer,
    private val context: Context? = null
) {
    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null

    var onDecibelUpdate: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDevicesUpdated: ((List<AudioInputDevice>) -> Unit)? = null
    var onExternalMicPlugged: ((AudioInputDevice) -> Unit)? = null

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null
    var selectedDevice: AudioInputDevice? = null
        private set

    private var isBluetoothScoActive = false
    var audioSourceMode: AudioSourceMode = AudioSourceMode.VOICE_RECOGNITION
    var isNoiseSuppressionEnabled: Boolean = true
    private var noiseSuppressor: NoiseSuppressor? = null

    init {
        context?.let { ctx ->
            audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            setupDeviceCallbacks()
        }
    }

    private fun setupDeviceCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val devices = getAvailableInputDevices()
                    onDevicesUpdated?.invoke(devices)

                    addedDevices?.forEach { dev ->
                        if (dev.isSource) {
                            val classified = classifyDevice(dev)
                            if (classified.isExternal) {
                                Log.i("AudioCaptureManager", "External mic connected: ${classified.name}")
                                onExternalMicPlugged?.invoke(classified)
                                setPreferredMicDevice(classified)
                            }
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val devices = getAvailableInputDevices()
                    onDevicesUpdated?.invoke(devices)

                    val currentId = selectedDevice?.id
                    if (removedDevices?.any { it.id == currentId } == true) {
                        val fallback = devices.firstOrNull { !it.isExternal } ?: devices.firstOrNull()
                        setPreferredMicDevice(fallback)
                    }
                }
            }
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
        }
    }

    fun getAvailableInputDevices(): List<AudioInputDevice> {
        val list = mutableListOf<AudioInputDevice>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            val devices = audioManager!!.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.forEach { dev ->
                list.add(classifyDevice(dev))
            }
        }
        if (list.none { !it.isExternal }) {
            list.add(0, AudioInputDevice(
                id = 0,
                name = "🎙️ Phone Studio Mic Array",
                type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                isExternal = false,
                deviceInfo = null
            ))
        }
        return list
    }

    private fun classifyDevice(dev: AudioDeviceInfo): AudioInputDevice {
        val (name, isExternal) = when (dev.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> {
                val prod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.productName.toString() else "USB"
                if (prod.contains("K9", ignoreCase = true) || prod.contains("Wireless", ignoreCase = true)) {
                    "📡 K9 Wireless Mic Receiver" to true
                } else {
                    "📡 USB External Mic ($prod)" to true
                }
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "🎧 3.5mm Lavalier / Headset" to true
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            26, // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                val prod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.productName.toString() else "Bluetooth"
                "📶 Bluetooth Headset Mic ($prod)" to true
            }
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "🎙️ Phone Studio Mic Array" to false
            else -> "🎙️ Microphone (${dev.type})" to false
        }
        return AudioInputDevice(dev.id, name, dev.type, isExternal, dev)
    }

    private fun startBluetoothSco() {
        val am = audioManager ?: return
        if (isBluetoothScoActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = am.availableCommunicationDevices
                val btDevice = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == 26 || // TYPE_BLE_HEADSET
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                if (btDevice != null) {
                    val res = am.setCommunicationDevice(btDevice)
                    isBluetoothScoActive = res
                    Log.i("AudioCaptureManager", "setCommunicationDevice: ${btDevice.productName} res=$res")
                } else {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    isBluetoothScoActive = true
                }
            } else {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                isBluetoothScoActive = true
                Log.i("AudioCaptureManager", "Started legacy Bluetooth SCO")
            }
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Error starting Bluetooth SCO: ${e.message}")
        }
    }

    private fun stopBluetoothSco() {
        val am = audioManager ?: return
        if (!isBluetoothScoActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Error stopping Bluetooth SCO: ${e.message}")
        }
        isBluetoothScoActive = false
    }

    fun setPreferredMicDevice(device: AudioInputDevice?): Boolean {
        selectedDevice = device
        val isBt = device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                   device?.type == 26 || // TYPE_BLE_HEADSET
                   device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

        if (isBt) {
            startBluetoothSco()
        } else {
            stopBluetoothSco()
        }

        val record = audioRecord ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val success = record.setPreferredDevice(device?.deviceInfo)
            Log.i("AudioCaptureManager", "Set preferred mic device to: ${device?.name}, success=$success")
            success
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) return true

        val isBt = selectedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                   selectedDevice?.type == 26 ||
                   selectedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

        if (isBt) {
            startBluetoothSco()
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val err = "AudioRecord min buffer size error for $sampleRate Hz"
            Log.e("AudioCaptureManager", err)
            onError?.invoke(err)
            return false
        }

        val bufferSize = max(minBufferSize, 960 * 2 * 4) // 4x 20ms frames

        // If Bluetooth SCO is selected, VOICE_COMMUNICATION is required by Android HAL
        val sourcesToTry = if (isBt) {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
        } else {
            val list = mutableListOf<Int>()
            list.add(audioSourceMode.audioSource)
            if (!list.contains(MediaRecorder.AudioSource.VOICE_RECOGNITION)) list.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (!list.contains(MediaRecorder.AudioSource.MIC)) list.add(MediaRecorder.AudioSource.MIC)
            if (!list.contains(MediaRecorder.AudioSource.CAMCORDER)) list.add(MediaRecorder.AudioSource.CAMCORDER)
            if (!list.contains(MediaRecorder.AudioSource.DEFAULT)) list.add(MediaRecorder.AudioSource.DEFAULT)
            list.toIntArray()
        }

        var initializedRecord: AudioRecord? = null
        for (source in sourcesToTry) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    initializedRecord = record
                    Log.i("AudioCaptureManager", "Initialized AudioRecord with source $source")
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Failed to init source $source: ${e.message}")
            }
        }

        if (initializedRecord == null) {
            val err = "Could not initialize AudioRecord with any audio source"
            Log.e("AudioCaptureManager", err)
            onError?.invoke(err)
            return false
        }

        audioRecord = initializedRecord

        // Route to selected input device (e.g. K9 USB Wireless Mic or Bluetooth)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && selectedDevice?.deviceInfo != null) {
            audioRecord?.setPreferredDevice(selectedDevice?.deviceInfo)
        }

        // Attach hardware DSP Noise Suppressor if enabled and available
        if (isNoiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(initializedRecord.audioSessionId)?.apply {
                    enabled = true
                }
                Log.i("AudioCaptureManager", "Hardware NoiseSuppressor active")
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Failed to attach NoiseSuppressor: ${e.message}")
            }
        }

        try {
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val err = "AudioRecord failed to enter RECORDING state"
                Log.e("AudioCaptureManager", err)
                onError?.invoke(err)
                stop()
                return false
            }

            isCapturing.set(true)

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val readChunk = ShortArray(480) // 10ms at 48kHz
                var lastDbReportTime = 0L

                // Clean digital studio pre-gain: VOICE_RECOGNITION already has optimal speech calibration
                val studioGain = if (audioSourceMode == AudioSourceMode.VOICE_RECOGNITION) 1.35f else 1.8f

                while (isCapturing.get()) {
                    val record = audioRecord ?: break
                    val readSamples = record.read(readChunk, 0, readChunk.size)
                    if (readSamples > 0) {
                        for (i in 0 until readSamples) {
                            val raw = readChunk[i].toFloat() * studioGain
                            // Soft-saturation curve to prevent digital clipping while preserving vocal dynamics
                            val norm = raw / 32767.0f
                            val softNorm = if (norm > 1.0f) {
                                1.0f - 1.0f / (norm + 1.0f)
                            } else if (norm < -1.0f) {
                                -(1.0f - 1.0f / (-norm + 1.0f))
                            } else {
                                norm - (norm * norm * norm) * 0.1666f
                            }
                            readChunk[i] = (softNorm.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                        }
                        ringBuffer.write(readChunk, 0, readSamples)

                        // Real-time VU level calculation (~30Hz update)
                        val now = System.currentTimeMillis()
                        if (now - lastDbReportTime >= 33) {
                            var maxAmp = 0
                            for (i in 0 until readSamples) {
                                val absVal = Math.abs(readChunk[i].toInt())
                                if (absVal > maxAmp) maxAmp = absVal
                            }
                            val db = if (maxAmp > 0) {
                                20.0f * log10(maxAmp.toFloat() / 32767.0f)
                            } else {
                                -60.0f
                            }
                            onDecibelUpdate?.invoke(max(-60.0f, db))
                            lastDbReportTime = now
                        }
                    } else if (readSamples < 0) {
                        Log.e("AudioCaptureManager", "AudioRecord read error code: $readSamples")
                        onError?.invoke("AudioRecord read error: $readSamples")
                        break
                    }
                }
            }, "MicRelay-AudioCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            return true
        } catch (e: Exception) {
            val err = "Exception starting AudioRecord: ${e.message}"
            Log.e("AudioCaptureManager", err, e)
            onError?.invoke(err)
            stop()
            return false
        }
    }

    fun stop() {
        isCapturing.set(false)
        try {
            captureThread?.join(500)
        } catch (ignored: InterruptedException) {}
        captureThread = null

        stopBluetoothSco()

        try {
            noiseSuppressor?.release()
        } catch (ignored: Exception) {}
        noiseSuppressor = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }

    fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            deviceCallback = null
        }
    }

    fun isRunning(): Boolean = isCapturing.get()
}
