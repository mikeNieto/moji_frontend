package com.mhm.moji_frontend

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager: Manages CameraX with front-facing camera only.
 * No preview is shown in UI — only ImageAnalysis for face detection.
 * Runs at ~10 fps.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var isRunning = false

    /** Callback invoked for each camera frame */
    var frameAnalyzer: ((ImageProxy) -> Unit)? = null

    /**
     * Start the camera with ImageAnalysis only (no preview).
     * Must be called from a LifecycleOwner context (Activity/Fragment).
     */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (isRunning) {
            Log.d(TAG, "Camera already running, ignoring start()")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindImageAnalysis(lifecycleOwner)
                isRunning = true
                Log.d(TAG, "Camera started successfully (front-facing, ImageAnalysis only)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}", e)
                StateManager.updateState(RobotState.ERROR)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindImageAnalysis(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        // Unbind any existing use cases first
        provider.unbindAll()

        // Front camera only — NEVER use back camera
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        // Create analysis executor
        analysisExecutor?.shutdown()
        analysisExecutor = Executors.newSingleThreadExecutor()

        // Configure ImageAnalysis at ~10 fps via backpressure strategy
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor!!) { imageProxy ->
                    frameAnalyzer?.invoke(imageProxy) ?: imageProxy.close()
                }
            }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.d(TAG, "ImageAnalysis bound to lifecycle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ImageAnalysis: ${e.message}", e)
        }
    }

    /**
     * Stop the camera and release resources.
     */
    fun stop() {
        try {
            cameraProvider?.unbindAll()
            analysisExecutor?.shutdown()
            analysisExecutor = null
            imageAnalysis = null
            isRunning = false
            Log.d(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}", e)
        }
    }

    fun isActive(): Boolean = isRunning
}


