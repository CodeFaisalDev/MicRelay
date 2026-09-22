package com.micrelay.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val isExternal: Boolean,
    val deviceInfo: AudioDeviceInfo?
)

/**
 * Manages low-latency microphone capture via AudioRecord.
 * Features:
 * - Dynamic external microphone routing (K9 wireless USB-C receiver, 3.5mm lavalier, Bluetooth)
 * - Hot-plug detection via AudioDeviceCallback
 * - Hardware DSP Noise Suppression (NoiseSuppressor) and AGC
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

    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    var isNoiseSuppressionEnabled: Boolean = true
    var isAgcEnabled: Boolean = true

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

    private val audioSourcesToTry = intArrayOf(
        MediaRecorder.AudioSource.CAMCORDER,   // Studio wideband microphone
        MediaRecorder.AudioSource.UNPROCESSED, // Raw studio ADC without telephony filters
        MediaRecorder.AudioSource.MIC,         // Standard microphone
        MediaRecorder.AudioSource.DEFAULT
    )

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
                                // Auto-switch to connected external microphone
                                setPreferredMicDevice(classified)
                            }
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val devices = getAvailableInputDevices()
                    onDevicesUpdated?.invoke(devices)

                    // If currently selected device was removed, fallback to built-in mic
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
        // Ensure built-in fallback is always present
        if (list.none { !it.isExternal }) {
            list.add(0, AudioInputDevice(
                id = 0,
                name = "🎙️ Phone Studio Mic Array",
                type = 15, // TYPE_BUILTIN_MIC
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
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "📶 Bluetooth Wireless Mic" to true
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "🎙️ Phone Studio Mic Array" to false
            else -> "🎙️ Microphone (${dev.type})" to false
        }
        return AudioInputDevice(dev.id, name, dev.type, isExternal, dev)
    }

    fun setPreferredMicDevice(device: AudioInputDevice?): Boolean {
        selectedDevice = device
        val record = audioRecord ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val success = record.setPreferredDevice(device?.deviceInfo)
            Log.i("AudioCaptureManager", "Set preferred mic device to: ${device?.name}, success=$success")
            success
        } else {
            true
        }
    }

    fun setNoiseSuppression(enabled: Boolean) {
        isNoiseSuppressionEnabled = enabled
        try {
            noiseSuppressor?.enabled = enabled
            Log.i("AudioCaptureManager", "NoiseSuppressor enabled=$enabled")
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Failed to toggle NoiseSuppressor: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val err = "AudioRecord min buffer size error for $sampleRate Hz"
            Log.e("AudioCaptureManager", err)
            onError?.invoke(err)
            return false
        }

        val bufferSize = max(minBufferSize, 960 * 2 * 4) // 4x 20ms frames

        var initializedRecord: AudioRecord? = null
        for (source in audioSourcesToTry) {
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

        // Route to selected input device (e.g. K9 USB Wireless Mic)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && selectedDevice?.deviceInfo != null) {
            audioRecord?.setPreferredDevice(selectedDevice?.deviceInfo)
        }

        // Attach hardware DSP Noise Suppressor & AGC
        try {
            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = isNoiseSuppressionEnabled
                    }
                    Log.i("AudioCaptureManager", "Hardware NoiseSuppressor active")
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sessionId)?.apply {
                        enabled = isAgcEnabled
                    }
                    Log.i("AudioCaptureManager", "Hardware AGC active")
                }
            }
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Error attaching audio effects: ${e.message}")
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

                while (isCapturing.get()) {
                    val record = audioRecord ?: break
                    val readSamples = record.read(readChunk, 0, readChunk.size)
                    if (readSamples > 0) {
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

        try {
            noiseSuppressor?.release()
        } catch (ignored: Exception) {}
        noiseSuppressor = null

        try {
            agc?.release()
        } catch (ignored: Exception) {}
        agc = null

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
