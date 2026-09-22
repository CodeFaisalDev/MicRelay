package com.micrelay.core.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless Fast Audio-Video Remuxer.
 * Merges CameraX's silent video track and MicRelay's AAC audio track into a single MP4 container
 * with interleaved PTS timestamps to ensure flawless Android MPEG4Writer compatibility.
 */
object AvFastRemuxer {

    fun remux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || !audioFile.exists() || videoFile.length() < 100) {
            Log.e("AvFastRemuxer", "Invalid input files: vExists=${videoFile.exists()} vLen=${videoFile.length()} aExists=${audioFile.exists()} aLen=${audioFile.length()}")
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

            if (videoTrackSourceIndex == -1) {
                Log.e("AvFastRemuxer", "No video track found in ${videoFile.name}")
                return false
            }

            muxer.start()
            muxerStarted = true

            val videoBuffer = ByteBuffer.allocateDirect(1024 * 1024)
            val audioBuffer = ByteBuffer.allocateDirect(256 * 1024)
            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()

            var hasVideo = true
            var hasAudio = (audioTrackSourceIndex != -1)

            // 3. Interleaved Write Loop by Presentation Timestamp (PTS)
            while (hasVideo || hasAudio) {
                val videoPts = if (hasVideo) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioPts = if (hasAudio) audioExtractor.sampleTime else Long.MAX_VALUE

                if (hasVideo && (videoPts <= audioPts || !hasAudio)) {
                    videoBufferInfo.offset = 0
                    val read = videoExtractor.readSampleData(videoBuffer, 0)
                    if (read >= 0) {
                        videoBufferInfo.size = read
                        videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        videoBufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(videoTrackMuxerIndex, videoBuffer, videoBufferInfo)
                        hasVideo = videoExtractor.advance()
                    } else {
                        hasVideo = false
                    }
                } else if (hasAudio) {
                    audioBufferInfo.offset = 0
                    val read = audioExtractor.readSampleData(audioBuffer, 0)
                    if (read >= 0) {
                        audioBufferInfo.size = read
                        audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioBufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioTrackMuxerIndex, audioBuffer, audioBufferInfo)
                        hasAudio = audioExtractor.advance()
                    } else {
                        hasAudio = false
                    }
                }
            }

            Log.i("AvFastRemuxer", "Remux completed successfully: ${outputFile.name} (${outputFile.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e("AvFastRemuxer", "Remux error: ${e.message}", e)
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
