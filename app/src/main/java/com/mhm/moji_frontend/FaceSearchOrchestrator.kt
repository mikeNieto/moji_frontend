package com.mhm.moji_frontend

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.mhm.moji_frontend.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FaceSearchOrchestrator: Coordinates camera activation and face detection
 * during the SEARCHING state.
 *
 * When SEARCHING begins:
 *   1. Activates CameraManager + FaceDetectorManager
 *   2. Starts face_search_timeout_ms timer (default 8000ms)
 *   3. Logs ESP32 search command (BLE will be implemented in Step 8)
 *
 * If face detected before timeout:
 *   - Cancels timer
 *   - Stops camera
 *   - Notifies callback with bounding box and ImageProxy for Step 6 (FaceNet)
 *
 * If timeout without face:
 *   - Stops camera
 *   - TTS: "No puedo verte. Por favor acércate al robot."
 *   - Transitions to IDLE
 */
@androidx.camera.core.ExperimentalGetImage
class FaceSearchOrchestrator(
    private val cameraManager: CameraManager,
    private val faceDetectorManager: FaceDetectorManager,
    private val ttsManager: TtsManager,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "FaceSearchOrchestrator"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var isSearching = false

    /**
     * Callback when a face is detected. Provides bounding box and ImageProxy
     * for downstream processing (FaceNet embedding in Step 6).
     * The ImageProxy MUST be closed by the consumer after use.
     */
    var onFaceFound: ((boundingBox: Rect, imageProxy: ImageProxy) -> Unit)? = null

    /**
     * Callback invoked when the interaction should start with the backend.
     * Called after face detection (or with "unknown" if no onFaceFound handler is set).
     * Parameters: personId, faceRecognized, faceConfidence
     */
    var onInteractionReady: ((personId: String, faceRecognized: Boolean, faceConfidence: Float) -> Unit)? = null

    /**
     * Start the face search process. Call this when entering SEARCHING state.
     * @param lifecycleOwner Required by CameraX for lifecycle binding.
     */
    fun startSearch(lifecycleOwner: LifecycleOwner) {
        if (isSearching) {
            Log.d(TAG, "Search already in progress, ignoring")
            return
        }
        isSearching = true

        Log.d(TAG, "Starting face search...")

        // Log ESP32 search command (BLE not implemented yet — Step 8)
        Log.d(TAG, "[ESP32-STUB] Would send search sequence: rotate_right → stop → rotate_left → stop")

        // Set up face detection callbacks
        faceDetectorManager.onFaceDetected = { boundingBox, imageProxy ->
            handleFaceDetected(boundingBox, imageProxy)
        }
        faceDetectorManager.onNoFace = {
            // No face in this frame — keep searching (timer handles timeout)
        }

        // Wire camera frames to face detector
        cameraManager.frameAnalyzer = { imageProxy ->
            if (isSearching) {
                faceDetectorManager.analyze(imageProxy)
            } else {
                imageProxy.close()
            }
        }

        // Start camera
        cameraManager.start(lifecycleOwner)

        // Start timeout timer
        val timeoutMs = appPreferences.faceSearchTimeoutMs.toLong()
        timeoutJob = coroutineScope.launch {
            Log.d(TAG, "Face search timeout started: ${timeoutMs}ms")
            delay(timeoutMs)

            // Timeout expired without finding a face
            if (isSearching) {
                Log.d(TAG, "Face search timeout! No face found in ${timeoutMs}ms")
                stopSearch()
                Log.d(TAG, "[ESP32-STUB] Would send STOP command")
                StateManager.updateSubtitle("No puedo verte. Por favor acércate a mi.")
                ttsManager.speak("No puedo verte. Por favor acércate a mi.") {
                    // Volver a IDLE solo después de que el TTS termine de hablar
                    StateManager.updateState(RobotState.IDLE)
                }
            }
        }
    }

    private fun handleFaceDetected(boundingBox: Rect, imageProxy: ImageProxy) {
        if (!isSearching) {
            imageProxy.close()
            return
        }

        Log.d(TAG, "Face found! BoundingBox: $boundingBox — cancelling timeout")

        // Cancel timeout — face was found in time
        timeoutJob?.cancel()
        timeoutJob = null

        // Stop searching
        isSearching = false

        // Log ESP32 stop command (BLE not implemented yet — Step 8)
        Log.d(TAG, "[ESP32-STUB] Would send STOP command to ESP32")

        // Stop camera (no longer needed for searching)
        cameraManager.stop()

        // Notify downstream (Step 6: FaceNet embedding + recognition)
        // For now, since Step 6 isn't implemented yet, we close the imageProxy
        // and transition appropriately
        if (onFaceFound != null) {
            onFaceFound?.invoke(boundingBox, imageProxy)
        } else {
            // Step 6 (FaceNet) not implemented yet — treat as unknown person
            Log.d(TAG, "Face detected, no FaceNet handler — treating as unknown person")
            Log.d(TAG, "Face bounding box: left=${boundingBox.left}, top=${boundingBox.top}, " +
                    "right=${boundingBox.right}, bottom=${boundingBox.bottom}")
            imageProxy.close()
            coroutineScope.launch {
                // Notify backend: interaction_start with unknown person
                onInteractionReady?.invoke("unknown", false, 0f)
                // Transition to GREETING briefly then LISTENING
                StateManager.updateState(RobotState.GREETING)
                kotlinx.coroutines.delay(500)
                StateManager.updateState(RobotState.LISTENING)
            }
        }
    }

    /**
     * Stop the face search (camera + detector + timer).
     * Safe to call even if not currently searching.
     */
    fun stopSearch() {
        isSearching = false
        timeoutJob?.cancel()
        timeoutJob = null
        cameraManager.stop()
        faceDetectorManager.onFaceDetected = null
        faceDetectorManager.onNoFace = null
        cameraManager.frameAnalyzer = null
        Log.d(TAG, "Face search stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopSearch()
        faceDetectorManager.close()
    }

    fun isActive(): Boolean = isSearching
}



