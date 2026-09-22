package com.micrelay.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local AAC Audio Encoder & Muxer.
 * Encodes audio from AudioRingBuffer into a standalone .m4a file in parallel with CameraX and network streaming.
 */
class AacAudioEncoder(
    private val ringBuffer: AudioRingBuffer,
    private val sampleRate: Int = 48000,
    private val bitrate: Int = 192000
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val isRecording = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun startRecording(outputM4aFile: File): Boolean {
        if (isRecording.get()) return true

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            mediaMuxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            audioTrackIndex = -1
            isRecording.set(true)

            workerThread = Thread({
                encodeLoop()
            }, "MicRelay-AacEncoderThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i("AacAudioEncoder", "Started AAC recording to ${outputM4aFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("AacAudioEncoder", "Failed to start AAC encoder: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    private fun encodeLoop() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmChunk = ShortArray(1024)
        var cursor = ringBuffer.getHead()

        var totalSamplesEncoded = 0L

        while (isRecording.get()) {
            val (readCount, newCursor) = ringBuffer.read(cursor, pcmChunk, pcmChunk.size)
            cursor = newCursor

            if (readCount > 0) {
                val inputIndex = codec.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inputIndex)
                    if (inputBuf != null) {
                        inputBuf.clear()
                        inputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmChunk, 0, readCount)
                        val ptsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                        codec.queueInputBuffer(inputIndex, 0, readCount * 2, ptsUs, 0)
                        totalSamplesEncoded += readCount
                    }
                }
            } else {
                try {
                    Thread.sleep(5)
                } catch (ignored: InterruptedException) {
                    break
                }
            }

            // Drain output: handle INFO_OUTPUT_FORMAT_CHANGED (-2) and encoded output buffers
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.i("AacAudioEncoder", "MediaMuxer started with format: $newFormat")
                    }
                } else if (outputIndex >= 0) {
                    if (muxerStarted && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val encodedBuffer = codec.getOutputBuffer(outputIndex)
                        if (encodedBuffer != null) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        // Drain EOS
        try {
            val eosIndex = codec.dequeueInputBuffer(10000)
            if (eosIndex >= 0) {
                val ptsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                codec.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outputIndex >= 0) {
                    if (muxerStarted && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val encodedBuffer = codec.getOutputBuffer(outputIndex)
                        if (encodedBuffer != null) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
                        }
                    }
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) break
                }
            }
            Log.i("AacAudioEncoder", "Finished AAC encoding: totalSamplesEncoded=$totalSamplesEncoded, muxerStarted=$muxerStarted")
        } catch (e: Exception) {
            Log.w("AacAudioEncoder", "Error draining EOS: ${e.message}")
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            workerThread?.join(1500)
        } catch (ignored: InterruptedException) {}
        workerThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null

        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (ignored: Exception) {}
        mediaMuxer = null
        muxerStarted = false
    }
}
