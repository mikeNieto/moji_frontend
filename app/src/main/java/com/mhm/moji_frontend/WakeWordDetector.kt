package com.mhm.moji_frontend

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class WakeWordDetector(
    private val context: Context,
    private val ttsManager: TtsManager,
    private val onWakeWordDetected: () -> Unit,
    /** When set, Porcupine only restarts if continuous listening is NOT active */
    var continuousListeningManager: ContinuousListeningManager? = null,
    private val sensitivity: Float = 0.7f
) {
    private var porcupineManager: PorcupineManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var stateObserverJob: Job? = null

    fun start() {
        try {
            // Porcupine AccessKey — separate from the backend API key
            val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY

            // Try to load custom keyword if exists in raw resources
            var keywordPath = ""
            val rawResId = context.resources.getIdentifier("hey_moji_wake", "raw", context.packageName)
            if (rawResId != 0) {
                val keywordFile = File(context.filesDir, "hey_moji_wake.ppn")
                if (!keywordFile.exists()) {
                    context.resources.openRawResource(rawResId).use { input ->
                        FileOutputStream(keywordFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                keywordPath = keywordFile.absolutePath
            }

            // Copy the spanish model from raw resources to files
            var modelPath = ""
            val modelResId = context.resources.getIdentifier("porcupine_params_es", "raw", context.packageName)
            if (modelResId != 0) {
                val modelFile = File(context.filesDir, "porcupine_params_es.pv")
                if (!modelFile.exists()) {
                    context.resources.openRawResource(modelResId).use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                modelPath = modelFile.absolutePath
            }

            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)

            if (keywordPath.isNotEmpty()) {
                builder.setKeywordPath(keywordPath)
                if (modelPath.isNotEmpty()) {
                    builder.setModelPath(modelPath)
                }
            } else {
                builder.setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE)
            }

            builder.setSensitivity(sensitivity)

            porcupineManager = builder.build(context, PorcupineManagerCallback { keywordIndex ->
                Log.d("WakeWordDetector", "Wake word detected! Index: $keywordIndex")
                handleWakeWordDetected()
            })

            porcupineManager?.start()
            Log.d("WakeWordDetector", "Porcupine started successfully")
        } catch (e: Exception) {
            Log.e("WakeWordDetector", "Failed to initialize Porcupine: ${e.message}", e)
        }
    }

    private fun handleWakeWordDetected() {
        // Interrumpir TTS si está activo
        ttsManager.stop()

        // Stop Porcupine on a DIFFERENT thread — we are currently on Porcupine's audio thread,
        // and calling stop() from within the callback would deadlock or corrupt internal state.
        coroutineScope.launch {
            try {
                porcupineManager?.stop()
                Log.d("WakeWordDetector", "Porcupine stopped to release microphone")
            } catch (e: Exception) {
                Log.e("WakeWordDetector", "Error stopping Porcupine for mic release", e)
            }

            // Now that Porcupine is stopped, transition to LISTENING briefly
            // then immediately to SEARCHING (camera face search)
            StateManager.updateState(RobotState.LISTENING)
            // Cancelar cualquier interacción activa
            onWakeWordDetected()
            // Transition to SEARCHING — the FaceSearchOrchestrator will be triggered
            StateManager.updateState(RobotState.SEARCHING)

            // Start observing state to restart Porcupine when recording is done
            startStateObserver()
        }
    }

    private fun startStateObserver() {
        // Cancel any existing observer
        stateObserverJob?.cancel()
        stateObserverJob = coroutineScope.launch {
            // Drop the first emission (current state = LISTENING that we just set)
            // and wait for a terminal state where we should restart Porcupine
            StateManager.currentState.drop(1).collect { state ->
                if (state == RobotState.IDLE || state == RobotState.ERROR || state == RobotState.DISCONNECTED) {
                    // Only restart Porcupine if continuous listening is NOT active
                    val clm = continuousListeningManager
                    if (clm != null && clm.isActive) {
                        Log.d("WakeWordDetector", "State=$state but continuous listening is active — NOT restarting Porcupine")
                        // Don't cancel observer — keep watching for when continuous listening ends
                        return@collect
                    }

                    Log.d("WakeWordDetector", "State returned to $state, restarting Porcupine")
                    // Small delay to ensure AudioRecorder has fully released the mic
                    kotlinx.coroutines.delay(500)
                    try {
                        porcupineManager?.start()
                        Log.d("WakeWordDetector", "Porcupine restarted successfully")
                    } catch (e: Exception) {
                        Log.e("WakeWordDetector", "Error restarting Porcupine, rebuilding", e)
                        // If restart fails, try full rebuild
                        try {
                            porcupineManager?.delete()
                            porcupineManager = null
                        } catch (_: Exception) {}
                        start()
                    }
                    // Stop observing after restart
                    stateObserverJob?.cancel()
                    stateObserverJob = null
                }
            }
        }
    }

    fun stop() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: Exception) {
            Log.e("WakeWordDetector", "Error stopping Porcupine", e)
        }
    }
}
