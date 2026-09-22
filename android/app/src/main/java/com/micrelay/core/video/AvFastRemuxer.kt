package com.micrelay.core.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless Fast Audio-Video Remuxer.
 * Merges CameraX's silent video track and MicRelay's AAC audio track into a single MP4 container
 * without transcoding (< 500ms execution).
 */
object AvFastRemuxer {

    fun remux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || !audioFile.exists()) {
            return false
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Locate video track
            var videoTrackSourceIndex = -1
            var videoTrackMuxerIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackSourceIndex = i
                    videoTrackMuxerIndex = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    break
                }
            }

            // 2. Locate audio track
            var audioTrackSourceIndex = -1
            var audioTrackMuxerIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackSourceIndex = i
                    audioTrackMuxerIndex = muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                    break
                }
            }

            if (videoTrackSourceIndex == -1 || audioTrackSourceIndex == -1) {
                return false
            }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            // 3. Copy Video elementary stream
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackMuxerIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 4. Copy Audio elementary stream
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackMuxerIndex, buffer, bufferInfo)
                audioExtractor.advance()
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                videoExtractor.release()
            } catch (ignored: Exception) {}
            try {
                audioExtractor.release()
            } catch (ignored: Exception) {}
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (ignored: Exception) {}
        }
    }
}
