package com.micrelay.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max

/**
 * Manages low-latency microphone capture via AudioRecord.
 * Prioritizes standard MediaRecorder.AudioSource.MIC for maximum device compatibility.
 */
class AudioCaptureManager(
    val sampleRate: Int = 48000,
    val ringBuffer: AudioRingBuffer
) {
    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null

    var onDecibelUpdate: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val audioSourcesToTry = intArrayOf(
        MediaRecorder.AudioSource.CAMCORDER,   // Studio wideband microphone (same as camera app)
        MediaRecorder.AudioSource.UNPROCESSED, // Raw studio ADC without telephony filters (API 24+)
        MediaRecorder.AudioSource.MIC,         // Standard microphone
        MediaRecorder.AudioSource.DEFAULT
    )


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
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }

    fun isRunning(): Boolean = isCapturing.get()
}
