package com.micrelay.core.video

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoStorageHelper {

    fun getOutputVideoFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VID_$timeStamp.mp4"

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appMoviesDir = File(moviesDir, "MicRelay")
        if (!appMoviesDir.exists()) {
            appMoviesDir.mkdirs()
        }

        return File(appMoviesDir, fileName)
    }

    fun getTempVideoFile(context: Context): File {
        return File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
    }

    fun getTempAudioFile(context: Context): File {
        return File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
    }

    fun notifyMediaScanner(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
