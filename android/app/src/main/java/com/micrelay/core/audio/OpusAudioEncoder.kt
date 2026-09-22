package com.micrelay.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus & PCM Frame Encoder for Network Streaming.
 * Encodes raw PCM samples from AudioRingBuffer into 20ms Opus frames (or 10ms PCM fallback frames).
 */
class OpusAudioEncoder(
    private val sampleRate: Int = 48000,
    private val bitrate: Int = 64000
) {
    private var mediaCodec: MediaCodec? = null
    private var useOpus = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        // Default to PCM for bit-exact studio quality and zero codec/decoding latency
        useOpus = false
    }


    /**
     * Converts raw PCM samples into network audio payload.
     * @param pcmSamples Input short array (e.g. 960 samples for 20ms).
     * @param onEncoded Callback returning (payloadType, bytePayload).
     */
    fun encode(pcmSamples: ShortArray, count: Int, onEncoded: (Byte, ByteArray) -> Unit) {
        if (!useOpus || mediaCodec == null) {
            // PCM 16-bit LE framing (10ms = 480 samples = 960 bytes, fits standard MTU)
            val pcmBytes = ByteArray(count * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmSamples, 0, count)
            onEncoded(2 /* PAYLOAD_TYPE_PCM */, pcmBytes)
            return
        }

        try {
            val codec = mediaCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(1000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                val shortBuffer = inputBuffer?.order(ByteOrder.LITTLE_ENDIAN)?.asShortBuffer()
                shortBuffer?.put(pcmSamples, 0, count)
                codec.queueInputBuffer(inputIndex, 0, count * 2, System.nanoTime() / 1000, 0)
            }

            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 1000)
            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val encodedBytes = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.get(encodedBytes, 0, bufferInfo.size)
                    onEncoded(1 /* PAYLOAD_TYPE_OPUS */, encodedBytes)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            // Fallback directly to PCM on any codec exception
            val pcmBytes = ByteArray(count * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmSamples, 0, count)
            onEncoded(2 /* PAYLOAD_TYPE_PCM */, pcmBytes)
        }
    }

    fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null
    }

    fun isOpusActive(): Boolean = useOpus
}
