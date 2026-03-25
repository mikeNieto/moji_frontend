package com.mhm.moji_frontend

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HeartbeatSender: Sends a heartbeat JSON message to the ESP32 every 1 second.
 *
 * The ESP32 expects a heartbeat every 1s. If it doesn't receive one in 3s:
 *   - Motors: STOP immediately
 *   - LEDs: amber pulsing ("brain disconnected" mode)
 *   - No new movement commands accepted until heartbeat is restored
 *
 * This protects the physical hardware if the Android app crashes or is killed.
 *
 * Usage:
 *   val sender = HeartbeatSender(esp32Protocol)
 *   sender.start()   // begins sending
 *   sender.stop()    // cancels the coroutine (e.g., when BLE disconnects)
 */
class HeartbeatSender(private val esp32Protocol: ESP32Protocol) {

    companion object {
        private const val TAG = "HeartbeatSender"
        private const val INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /**
     * Start sending heartbeats every 1 second.
     * Idempotent — calling start() when already running is a no-op.
     */
    fun start() {
        if (job?.isActive == true) {
            Log.d(TAG, "HeartbeatSender already running")
            return
        }
        job = scope.launch {
            Log.d(TAG, "HeartbeatSender started")
            while (isActive) {
                val timestamp = System.currentTimeMillis()
                val sent = esp32Protocol.sendHeartbeat(timestamp)
                if (!sent) {
                    Log.w(TAG, "Heartbeat send failed (BLE not ready?) — will retry next tick")
                }
                delay(INTERVAL_MS)
            }
            Log.d(TAG, "HeartbeatSender stopped")
        }
    }

    /**
     * Stop sending heartbeats.
     * Call this when BLE disconnects or app is shutting down.
     */
    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "HeartbeatSender cancelled")
    }

    /**
     * Whether the heartbeat coroutine is currently active.
     */
    fun isRunning(): Boolean = job?.isActive == true
}

