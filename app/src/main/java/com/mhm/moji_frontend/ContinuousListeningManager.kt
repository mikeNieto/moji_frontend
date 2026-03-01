package com.mhm.moji_frontend

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ContinuousListeningManager: Manages the 60-second continuous listening window.
 *
 * After the first interaction, Moji enters continuous listening mode:
 * - The user can keep talking without repeating "Hey Moji"
 * - Each successful interaction resets the 60s countdown
 * - When 60s pass without activity → returns to IDLE
 * - The wake word only becomes necessary again after returning to IDLE
 */
class ContinuousListeningManager {

    companion object {
        private const val TAG = "ContinuousListening"
        private const val CONTINUOUS_LISTEN_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var _isActive = false

    val isActive: Boolean get() = _isActive

    /**
     * Start or restart the continuous listening countdown.
     * Called after each successful interaction (stream_end received).
     */
    fun startOrReset() {
        _isActive = true
        timeoutJob?.cancel()

        Log.d(TAG, "Continuous listening started/reset (60s countdown)")

        timeoutJob = scope.launch {
            delay(CONTINUOUS_LISTEN_TIMEOUT_MS)
            Log.d(TAG, "Continuous listening timeout — returning to IDLE")
            _isActive = false
            StateManager.updateState(RobotState.IDLE)
        }
    }

    /**
     * Stop continuous listening mode (e.g., when manually going to IDLE or ERROR).
     */
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isActive = false
        Log.d(TAG, "Continuous listening stopped")
    }
}

