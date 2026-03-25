package com.mhm.moji_frontend

import android.util.Log

/**
 * ContinuousListeningManager: Tracks whether follow-up listening is active.
 *
 * The short no-speech timeout is enforced by AudioRecorder via
 * INITIAL_GRACE_PERIOD_MS. This manager only keeps the wake-word flow in
 * follow-up mode until the interaction returns to a terminal state.
 */
class ContinuousListeningManager {

    companion object {
        private const val TAG = "ContinuousListening"
    }

    private var _isActive = false

    val isActive: Boolean get() = _isActive

    /**
     * Mark continuous listening as active for the next follow-up input.
     * Called after each successful interaction (stream_end received).
     */
    fun startOrReset() {
        val wasActive = _isActive
        _isActive = true
        Log.d(TAG, if (wasActive) {
            "Continuous listening remains active for follow-up capture"
        } else {
            "Continuous listening activated for follow-up capture"
        })
    }

    /**
     * Stop continuous listening mode (e.g., when returning to IDLE/ERROR).
     */
    fun stop() {
        if (_isActive) {
            Log.d(TAG, "Continuous listening stopped")
        }
        _isActive = false
    }
}
