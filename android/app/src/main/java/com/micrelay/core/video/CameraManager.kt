package com.micrelay.core.video

import android.content.Context
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
 * Eliminates audio HAL preemption conflicts with AudioRecord.
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
            onReady()
        }, ContextCompat.getMainExecutor(context))
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

        // Configure video recorder for high-quality video ONLY
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)))
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

        // Notice: withAudioEnabled() is explicitly NOT called to prevent CameraX from opening AudioRecord!
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


    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
