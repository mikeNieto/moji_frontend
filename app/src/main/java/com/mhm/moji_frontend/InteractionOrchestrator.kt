package com.mhm.moji_frontend

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.mhm.moji_frontend.data.AppPreferences
import com.mhm.moji_frontend.data.ExpressionData
import com.mhm.moji_frontend.data.WsIncoming
import com.mhm.moji_frontend.data.WsOutgoing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * InteractionOrchestrator: The central coordinator for the full interaction flow.
 *
 * Listens to WebSocket messages and orchestrates:
 * - Emotion tag → transition to responding, but defer visible emojis to response_meta
 * - Text chunks → accumulate and feed to TTS in sentence chunks
 * - Capture requests → activate camera, capture photo/video, send back
 * - Response meta → show emoji sequences, log ESP32 actions (BLE in Step 8)
 * - Stream end → enter continuous listening mode
 * - Person registered → store face embedding in local DB (when Room is available)
 * - Error messages → handle gracefully
 *
 * Also manages:
 * - Sending interaction_start when face recognition completes
 * - Sending audio data captured by AudioRecorder
 * - Sending audio_end after the last audio frame
 * - Continuous listening mode (brief window without wake word)
 */
class InteractionOrchestrator(
    private val context: Context,
    private val wsClient: RobotWebSocketClient,
    private val ttsManager: TtsManager,
    private val cameraManager: CameraManager,
    private val preferences: AppPreferences,
    private val esp32Protocol: ESP32Protocol? = null
) {
    companion object {
        private const val TAG = "InteractionOrchestrator"
        private const val RESPONSE_ACTION_DELAY_AFTER_TTS_START_MS = 1000L
        private const val TTS_START_WAIT_TIMEOUT_MS = 2500L
        private const val LEAD_IN_EMOJI_DURATION_MS = 1000L
        private const val FINAL_EMOJI_FALLBACK_DURATION_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    val continuousListeningManager = ContinuousListeningManager()

    // Text chunk accumulation for TTS
    private val textChunkFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var ttsJob: Job? = null
    private var messageCollectorJob: Job? = null
    private var emojiSequenceJob: Job? = null
    private var pendingResponseActionsJob: Job? = null

    // Current interaction tracking
    private var currentRequestId: String? = null

    // Last captured face embedding (for person_registered storage)
    var lastCapturedEmbedding: FloatArray? = null

    // Robot battery (from BLE telemetry — Step 8 will provide real values)
    var robotBatteryLevel: Int = 100

    // Callback for person_registered events (to store in Room DB if available)
    var onPersonRegistered: ((personId: String, name: String) -> Unit)? = null

    /**
     * Start listening to WebSocket messages.
     */
    fun start() {
        messageCollectorJob?.cancel()
        messageCollectorJob = scope.launch(Dispatchers.Main) {
            wsClient.incomingMessages.collect { message ->
                handleMessage(message)
            }
        }

        // Also observe connection state changes
        scope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    RobotWebSocketClient.ConnectionState.DISCONNECTED -> {
                        Log.d(TAG, "WebSocket disconnected")
                        StateManager.updateBackendConnection(false)
                    }
                    RobotWebSocketClient.ConnectionState.AUTHENTICATED -> {
                        Log.d(TAG, "WebSocket authenticated — ready for interactions")
                        StateManager.updateBackendConnection(true)
                        StateManager.markBackendIssue(false)
                        // If we were DISCONNECTED, restore to IDLE
                        if (StateManager.currentState.value == RobotState.DISCONNECTED) {
                            StateManager.updateState(RobotState.IDLE)
                        }
                    }
                    else -> { /* CONNECTING, CONNECTED — intermediate states */ }
                }
            }
        }

        Log.d(TAG, "InteractionOrchestrator started")
    }

    /**
     * Stop the orchestrator.
     */
    fun stop() {
        messageCollectorJob?.cancel()
        ttsJob?.cancel()
        emojiSequenceJob?.cancel()
        pendingResponseActionsJob?.cancel()
        continuousListeningManager.stop()
        Log.d(TAG, "InteractionOrchestrator stopped")
    }

    // ======================== OUTGOING: Interaction Start ========================

    /**
     * Begin a new interaction with the backend.
     * Called after face recognition completes (GREETING or REGISTERING state).
     *
     * @param personId The recognized person ID, or "unknown" for new faces
     * @param faceRecognized Whether the face was recognized
     * @param faceConfidence The confidence score (0.0-1.0)
     * @param faceEmbedding The face embedding as base64, only for unknown faces
     */
    fun startInteraction(
        personId: String,
        faceRecognized: Boolean,
        faceConfidence: Float,
        faceEmbedding: String? = null
    ) {
        currentRequestId = UUID.randomUUID().toString()

        val msg = WsOutgoing.InteractionStart(
            requestId = currentRequestId!!,
            personId = personId,
            faceRecognized = faceRecognized,
            faceConfidence = faceConfidence,
            faceEmbedding = faceEmbedding,
            batteryRobot = robotBatteryLevel,
            batteryPhone = getPhoneBatteryLevel()
        )
        wsClient.sendMessage(msg)
        Log.d(TAG, "interaction_start sent: requestId=$currentRequestId, personId=$personId")
    }

    /**
     * Send captured audio data to the backend as binary frames.
     */
    fun sendAudioData(audioData: ByteArray) {
        if (!wsClient.isReady()) {
            Log.w(TAG, "Cannot send audio: WebSocket not ready")
            return
        }
        wsClient.sendAudioData(audioData)

        // Send audio_end after the audio data
        val requestId = currentRequestId ?: UUID.randomUUID().toString()
        wsClient.sendMessage(WsOutgoing.AudioEnd(requestId))
        Log.d(TAG, "Audio data sent (${audioData.size} bytes) + audio_end")
    }

    /**
     * Send a text message (alternative to audio, for testing).
     */
    fun sendTextMessage(text: String, personId: String = "unknown") {
        val requestId = currentRequestId ?: UUID.randomUUID().toString()
        currentRequestId = requestId
        wsClient.sendMessage(WsOutgoing.Text(
            requestId = requestId,
            content = text,
            personId = personId
        ))
        Log.d(TAG, "Text message sent: $text")
    }

    /**
     * Send a battery alert when battery is critically low.
     */
    fun sendBatteryAlert(level: Int, source: String) {
        val requestId = UUID.randomUUID().toString()
        wsClient.sendMessage(WsOutgoing.BatteryAlert(
            requestId = requestId,
            batteryLevel = level,
            source = source
        ))
    }

    // ======================== INCOMING: Message Handling ========================

    private fun handleMessage(message: WsIncoming) {
        when (message) {
            is WsIncoming.AuthOk -> {
                Log.d(TAG, "Auth confirmed, session: ${message.sessionId}")
            }

            is WsIncoming.EmotionMessage -> handleEmotion(message)
            is WsIncoming.TextChunk -> handleTextChunk(message)
            is WsIncoming.CaptureRequest -> handleCaptureRequest(message)
            is WsIncoming.ResponseMeta -> handleResponseMeta(message)
            is WsIncoming.StreamEnd -> handleStreamEnd(message)
            is WsIncoming.PersonRegistered -> handlePersonRegistered(message)
            is WsIncoming.FaceScanActions -> handleFaceScanActions(message)
            is WsIncoming.ErrorMessage -> handleError(message)
            is WsIncoming.Unknown -> {
                Log.w(TAG, "Unknown message received: ${message.type}")
            }
        }
    }

    /**
     * Handle emotion tag — move to RESPONDING and prepare TTS.
     * The visible emoji is now driven by response_meta.expression.emojis.
     */
    private fun handleEmotion(msg: WsIncoming.EmotionMessage) {
        Log.d(TAG, "Emotion received: ${msg.emotion} (person: ${msg.personIdentified})")

        emojiSequenceJob?.cancel()

        // Update state to RESPONDING
        StateManager.updateState(RobotState.RESPONDING)

        // Intentionally disabled: this early emotion arrives before the contextual emoji sequence.
        // currentEmotionShownAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        // StateManager.updateEmotion(msg.emotion)

        // Start TTS chunked flow for this interaction
        startTtsStreaming()
    }

    /**
     * Handle text chunk — accumulate and feed to TTS.
     */
    private fun handleTextChunk(msg: WsIncoming.TextChunk) {
        Log.d(TAG, "Text chunk: '${msg.text}'")

        // Update subtitle with accumulated text
        StateManager.updateSubtitle(msg.text)

        // Emit to TTS flow
        scope.launch {
            textChunkFlow.emit(msg.text)
        }
    }

    /**
     * Handle capture request — take a photo or video and send back.
     */
    private fun handleCaptureRequest(msg: WsIncoming.CaptureRequest) {
        Log.d(TAG, "Capture request: ${msg.captureType}, duration: ${msg.durationMs}ms")

        when (msg.captureType) {
            "photo" -> {
                // TODO: Implement photo capture via CameraX
                // For now, log the request. Full capture will use CameraManager.
                Log.d(TAG, "[CAPTURE-STUB] Would capture photo and send as base64 JPEG")
            }
            "video" -> {
                // TODO: Implement video capture
                Log.d(TAG, "[CAPTURE-STUB] Would capture video (${msg.durationMs}ms) and send as base64 MP4")
            }
            else -> {
                Log.w(TAG, "Unknown capture type: ${msg.captureType}")
            }
        }
    }

    /**
     * Handle response metadata — show emoji sequences and send ESP32 actions.
     */
    private fun handleResponseMeta(msg: WsIncoming.ResponseMeta) {
        Log.d(TAG, "Response meta: text='${msg.responseText}', personName=${msg.personName}")

        // If backend deduced a new person name, store it
        msg.personName?.let { name ->
            Log.d(TAG, "Backend deduced person name: $name")
            // This would trigger saving to Room DB when Step 6 is fully integrated
        }

        // Show emoji sequence from response data
        msg.expression?.let { expr ->
            showEmojiSequence(expr)
        }

        // Send physical actions to ESP32 via BLE 1s after TTS actually starts
        if (msg.actions.isNotEmpty()) {
            Log.d(TAG, "Scheduling ${msg.actions.size} actions for ESP32 via BLE")
            pendingResponseActionsJob?.cancel()
            pendingResponseActionsJob = scope.launch {
                val remainingDelay = ttsManager.getRemainingDelayUntilSpeechStartOffset(
                    offsetMs = RESPONSE_ACTION_DELAY_AFTER_TTS_START_MS,
                    timeoutMs = TTS_START_WAIT_TIMEOUT_MS
                )

                if (remainingDelay == null) {
                    Log.w(TAG, "TTS did not start in time; sending BLE actions without extra delay")
                } else if (remainingDelay > 0) {
                    Log.d(TAG, "Waiting ${remainingDelay}ms before sending BLE actions")
                    delay(remainingDelay)
                }

                esp32Protocol?.executeActions(msg.actions)
                    ?: msg.actions.forEach { action ->
                        Log.d(
                            TAG,
                            "[ESP32-STUB] ${action.type}: degrees=${action.degrees}, cm=${action.cm}, " +
                                "duration=${action.durationMs}ms, r=${action.r}, g=${action.g}, b=${action.b}, " +
                                "steps=${action.steps?.size ?: 0}"
                        )
                    }
            }
        }
    }

    /**
     * Handle stream end — interaction complete, enter continuous listening.
     */
    private fun handleStreamEnd(msg: WsIncoming.StreamEnd) {
        Log.d(TAG, "Stream ended (processing time: ${msg.processingTimeMs}ms)")
        currentRequestId = null

        // Wait for TTS to finish before activating the microphone.
        scope.launch {
            delay(300)

            val ttsStarted = withTimeoutOrNull(2000L) {
                ttsManager.isSpeaking.first { it }
            }
            if (ttsStarted != null) {
                ttsManager.isSpeaking.first { !it }
            }

            emojiSequenceJob?.cancel()

            // Enter continuous listening mode for the next input.
            continuousListeningManager.startOrReset()
            StateManager.updateState(RobotState.LISTENING)
            StateManager.updateSubtitle("Te escucho...")

            Log.d(TAG, "TTS finished — emoji sequence stopped and continuous listening active")
        }
    }

    /**
     * Handle person registered — store embedding locally.
     */
    private fun handlePersonRegistered(msg: WsIncoming.PersonRegistered) {
        Log.d(TAG, "Person registered: ${msg.name} (${msg.personId})")

        // Invoke callback to store in Room DB (if Step 6 provides the handler)
        onPersonRegistered?.invoke(msg.personId, msg.name)

        // Also notify the subtitle
        StateManager.updateSubtitle("¡Mucho gusto, ${msg.name}!")
    }

    /**
     * Handle face scan actions — ESP32 rotation commands.
     */
    private fun handleFaceScanActions(msg: WsIncoming.FaceScanActions) {
        Log.d(TAG, "Face scan actions received: ${msg.actions.size} actions")
        esp32Protocol?.executeActions(msg.actions)
            ?: msg.actions.forEach { action ->
                Log.d(
                    TAG,
                    "[ESP32-STUB] ${action.type}: degrees=${action.degrees}, cm=${action.cm}, duration=${action.durationMs}ms"
                )
            }
    }

    /**
     * Handle error messages from the backend.
     */
    private fun handleError(msg: WsIncoming.ErrorMessage) {
        Log.e(TAG, "Backend error: [${msg.errorCode}] ${msg.message} (recoverable: ${msg.recoverable})")

        if (msg.recoverable) {
            // Show error temporarily (2s) then go back to IDLE
            StateManager.updateState(RobotState.ERROR)
            StateManager.updateSubtitle(msg.message)
            scope.launch {
                delay(2000)
                StateManager.updateState(RobotState.IDLE)
            }
        } else {
            // Non-recoverable error — show and stay
            StateManager.updateState(RobotState.ERROR)
            StateManager.updateSubtitle("Error: ${msg.message}")
        }
    }

    // ======================== TTS Streaming ========================

    private fun startTtsStreaming() {
        // Cancel any existing TTS job
        ttsJob?.cancel()
        pendingResponseActionsJob?.cancel()
        ttsJob = ttsManager.speakChunked(textChunkFlow)
    }

    // ======================== Emoji Sequence ========================

    /**
     * Show the contextual emojis from response_meta.
     * The first emojis are shown for 1s each, and the final one stays until TTS finishes.
     */
    private fun showEmojiSequence(expression: ExpressionData) {
        emojiSequenceJob?.cancel()

        val plan = EmojiSequencePlanner.createPlan(expression.emojis)
        val finalEmoji = plan.finalEmoji
        if (finalEmoji == null) {
            Log.d(TAG, "Response meta contained no contextual emojis")
            return
        }

        emojiSequenceJob = scope.launch {
            Log.d(
                TAG,
                "Response meta contained ${expression.emojis.size} emojis; " +
                    "showing ${plan.leadInEmojis.size} lead-in emojis and holding the final emoji"
            )

            for (emojiCode in plan.leadInEmojis) {
                Log.d(TAG, "Showing lead-in contextual emoji for ${LEAD_IN_EMOJI_DURATION_MS}ms: $emojiCode (${expression.transition})")
                StateManager.updateEmotion(emojiCode)
                delay(LEAD_IN_EMOJI_DURATION_MS)
            }

            Log.d(TAG, "Showing final contextual emoji until TTS finishes: $finalEmoji (${expression.transition})")
            StateManager.updateEmotion(finalEmoji)

            val speechSessionStarted = ttsManager.getRemainingDelayUntilSpeechStartOffset(
                offsetMs = 0L,
                timeoutMs = TTS_START_WAIT_TIMEOUT_MS
            ) != null

            if (!speechSessionStarted) {
                Log.w(TAG, "TTS did not start in time; keeping final emoji briefly as fallback")
                delay(FINAL_EMOJI_FALLBACK_DURATION_MS)
                return@launch
            }

            if (ttsManager.isSpeaking.value) {
                ttsManager.isSpeaking.first { !it }
            }
        }
    }

    // ======================== Utility ========================

    private fun getPhoneBatteryLevel(): Int {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get phone battery level: ${e.message}")
            -1
        }
    }
}
