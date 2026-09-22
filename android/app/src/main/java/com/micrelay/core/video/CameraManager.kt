package com.micrelay.core.video

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX Video Capture Manager (Strictly Video-Only).
 * Features:
 * - Selectable Resolution: 4K UHD, 1080p FHD, 720p HD
 * - Modern Hardware Codec Detection (HEVC/H.265, AV1, H.264)
 * - Zero audio HAL conflict with AudioRecord
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    var currentQuality: Quality = Quality.FHD
        private set

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun setQuality(quality: Quality, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        currentQuality = quality
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        // Configure video recorder for requested resolution profile
        val qualitySelector = QualitySelector.from(
            currentQuality,
            FallbackStrategy.higherQualityOrLowerThan(currentQuality)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(cameraExecutor)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        try {
            if (provider.hasCamera(cameraSelector)) {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    private var finalizeCallback: ((Boolean) -> Unit)? = null

    /**
     * Starts recording video ONLY to a temporary MP4 file.
     */
    fun startRecording(outputFile: File, onFinished: (Boolean) -> Unit) {
        val capture = videoCapture ?: return
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        this.finalizeCallback = onFinished

        // Notice: withAudioEnabled() is explicitly NOT called to prevent CameraX from preempting AudioRecord
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        val success = !event.hasError()
                        finalizeCallback?.invoke(success)
                        finalizeCallback = null
                    }
                }
            }
    }

    fun stopRecording(onFinalized: ((Boolean) -> Unit)? = null) {
        if (onFinalized != null) {
            this.finalizeCallback = onFinalized
        }
        activeRecording?.stop()
        activeRecording = null
    }

    /**
     * Inspects device hardware to report supported video encoders (HEVC, AV1, AVC).
     */
    fun getSupportedHardwareCodecs(): List<String> {
        val codecs = mutableListOf<String>()
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type) {
                        MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                            if (!codecs.contains("HEVC (H.265)")) codecs.add("HEVC (H.265)")
                        }
                        "video/av01" -> {
                            if (!codecs.contains("AV1")) codecs.add("AV1")
                        }
                        MediaFormat.MIMETYPE_VIDEO_AVC -> {
                            if (!codecs.contains("H.264 (AVC)")) codecs.add("H.264 (AVC)")
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        if (codecs.isEmpty()) {
            codecs.add("H.264 (AVC)")
        }
        return codecs
    }

    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
