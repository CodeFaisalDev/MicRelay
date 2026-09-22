package com.micrelay.core.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoStorageHelper {

    fun getTempVideoFile(context: Context): File {
        return File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
    }

    fun getTempAudioFile(context: Context): File {
        return File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
    }

    fun getOutputVideoFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VID_$timeStamp.mp4"
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appMoviesDir = File(moviesDir, "MicRelay")
        if (!appMoviesDir.exists()) {
            try { appMoviesDir.mkdirs() } catch (ignored: Exception) {}
        }
        return File(appMoviesDir, fileName)
    }

    /**
     * Exports a locally muxed MP4 file to the user's public Movies/MicRelay gallery.
     * Uses Scoped Storage MediaStore on Android 10+ (API 29+) to bypass all permission blocks.
     */
    fun exportToPublicMovies(context: Context, sourceFile: File): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VID_$timeStamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MicRelay")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Log.i("VideoStorageHelper", "Exported via MediaStore to Movies/MicRelay: $fileName")
                    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MicRelay/$fileName")
                }
            } catch (e: Exception) {
                Log.w("VideoStorageHelper", "MediaStore export failed, attempting direct file fallback: ${e.message}")
            }
        }

        // Direct file fallback for Android 9 or if MediaStore insertion fails
        try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val appMoviesDir = File(moviesDir, "MicRelay")
            if (!appMoviesDir.exists()) {
                appMoviesDir.mkdirs()
            }
            val destFile = File(appMoviesDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            notifyMediaScanner(context, destFile)
            Log.i("VideoStorageHelper", "Exported via direct File copy: ${destFile.absolutePath}")
            return destFile
        } catch (e: Exception) {
            Log.e("VideoStorageHelper", "Direct file copy failed: ${e.message}", e)
        }

        // Safe app-specific fallback
        try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val destFile = File(fallbackDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            notifyMediaScanner(context, destFile)
            return destFile
        } catch (e: Exception) {
            Log.e("VideoStorageHelper", "All export attempts failed: ${e.message}")
            return null
        }
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
