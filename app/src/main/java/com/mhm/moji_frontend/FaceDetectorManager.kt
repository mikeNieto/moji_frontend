package com.mhm.moji_frontend

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * FaceDetectorManager: Wraps ML Kit Face Detection.
 * Configured for speed (no landmarks, no classification, only bounding boxes).
 * Provides callbacks: onFaceDetected(boundingBox, imageProxy) and onNoFace().
 */
@androidx.camera.core.ExperimentalGetImage
class FaceDetectorManager {

    companion object {
        private const val TAG = "FaceDetectorManager"
    }

    /** Callback when at least one face is detected. Provides bounding box and the original ImageProxy. */
    var onFaceDetected: ((boundingBox: Rect, imageProxy: ImageProxy) -> Unit)? = null

    /** Callback when no face is found in the current frame. */
    var onNoFace: (() -> Unit)? = null

    // ML Kit face detector — fast mode, no landmarks, only bounding boxes
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f) // Detect faces at least 15% of image width
            .build()
    )

    /**
     * Analyze a camera frame for faces. Must close the ImageProxy when done.
     * This is meant to be called from CameraManager's frameAnalyzer callback.
     */
    fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    // Take the largest face (most likely the closest person)
                    val largestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (largestFace != null) {
                        Log.d(TAG, "Face detected! BoundingBox: ${largestFace.boundingBox}")
                        onFaceDetected?.invoke(largestFace.boundingBox, imageProxy)
                        return@addOnSuccessListener
                    }
                }
                // No faces found
                onNoFace?.invoke()
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}", e)
                onNoFace?.invoke()
                imageProxy.close()
            }
    }

    /**
     * Release the ML Kit detector resources.
     */
    fun close() {
        detector.close()
        Log.d(TAG, "Face detector closed")
    }
}



