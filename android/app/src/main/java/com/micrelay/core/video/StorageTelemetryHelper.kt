package com.micrelay.core.video

import android.os.Environment
import android.os.StatFs
import androidx.camera.video.Quality

object StorageTelemetryHelper {

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    fun getAvailableStorageGb(): Float {
        val bytes = getAvailableStorageBytes()
        return (bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).toFloat()
    }

    /**
     * Estimates remaining recording minutes based on selected camera resolution and average HEVC/AVC bitrate.
     */
    fun getEstimatedRecordingMinutes(quality: Quality): Long {
        val bytes = getAvailableStorageBytes()
        if (bytes <= 0) return 0L

        // Average mega-bytes per minute for video + 256kbps audio:
        val mbPerMinute = when (quality) {
            Quality.UHD -> 260L  // ~35 Mbps
            Quality.FHD -> 105L  // ~14 Mbps
            Quality.HD -> 45L    // ~6 Mbps
            else -> 20L          // ~2.5 Mbps
        }

        val availableMb = bytes / (1024L * 1024L)
        // Reserve 500MB safety margin
        val usableMb = (availableMb - 500L).coerceAtLeast(0L)
        return usableMb / mbPerMinute
    }

    fun formatRemainingTime(minutes: Long): String {
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            "${h}h ${m}m"
        } else {
            "${minutes}m"
        }
    }
}
