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
 * - Emotion tag → update robot face IMMEDIATELY
 * - Text chunks → accumulate and feed to TTS in sentence chunks
 * - Capture requests → activate camera, capture photo/video, send back
 * - Response meta → show emoji sequences, log ESP32 actions (BLE in Step 8)
 * - Stream end → enter continuous listening mode (60s)
 * - Person registered → store face embedding in local DB (when Room is available)
 * - Error messages → handle gracefully
 *
 * Also manages:
 * - Sending interaction_start when face recognition completes
 * - Sending audio data captured by AudioRecorder
 * - Sending audio_end after the last audio frame
 * - Continuous listening mode (60s window without wake word)
 */
class InteractionOrchestrator(
    private val context: Context,
    private val wsClient: RobotWebSocketClient,
    private val ttsManager: TtsManager,
    private val cameraManager: CameraManager,
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "InteractionOrchestrator"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    val continuousListeningManager = ContinuousListeningManager()

    // Text chunk accumulation for TTS
    private val textChunkFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var ttsJob: Job? = null
    private var messageCollectorJob: Job? = null
    private var emojiSequenceJob: Job? = null

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
     * Handle emotion tag — update robot face IMMEDIATELY.
     * This is the FIRST message of each interaction response.
     */
    private fun handleEmotion(msg: WsIncoming.EmotionMessage) {
        Log.d(TAG, "Emotion received: ${msg.emotion} (person: ${msg.personIdentified})")

        // Update state to RESPONDING
        StateManager.updateState(RobotState.RESPONDING)

        // Update emotion IMMEDIATELY — before TTS starts
        StateManager.updateEmotion(msg.emotion)

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

        // Show emoji sequence from expression data
        msg.expression?.let { expr ->
            showEmojiSequence(expr)
        }

        // Log ESP32 actions (BLE will be implemented in Step 8)
        if (msg.actions.isNotEmpty()) {
            Log.d(TAG, "[ESP32-STUB] Would send ${msg.actions.size} actions to ESP32:")
            msg.actions.forEach { action ->
                Log.d(TAG, "  → ${action.type}: speed=${action.speed}, degrees=${action.degrees}, " +
                        "duration=${action.durationMs}ms, r=${action.r}, g=${action.g}, b=${action.b}")
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
        // This prevents the mic from capturing TTS speaker output (acoustic echo).
        scope.launch {
            // Give the TTS engine time to start speaking (stream_end can arrive
            // before the last utterance has been queued/started by the engine).
            delay(300)

            // Wait for TTS to START speaking first (avoids the race where isSpeaking
            // is still false when stream_end arrives, causing first { !it } to resolve instantly)
            val ttsStarted = withTimeoutOrNull(2000L) {
                ttsManager.isSpeaking.first { it }
            }
            if (ttsStarted != null) {
                // TTS started — now wait until it fully finishes
                ttsManager.isSpeaking.first { !it }
            }

            // Extra buffer to let the speaker echo fully decay before opening the mic
            delay(1200)

            // Enter continuous listening mode (60s window)
            continuousListeningManager.startOrReset()

            // Transition to LISTENING for the next input
            StateManager.updateState(RobotState.LISTENING)
            StateManager.updateSubtitle("Te escucho...")

            Log.d(TAG, "Continuous listening active (60s window)")
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
        Log.d(TAG, "[ESP32-STUB] Face scan actions received: ${msg.actions.size} actions")
        msg.actions.forEach { action ->
            Log.d(TAG, "  → ${action.type}: degrees=${action.degrees}, speed=${action.speed}, " +
                    "duration=${action.durationMs}ms")
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
        ttsJob = ttsManager.speakChunked(textChunkFlow)
    }

    // ======================== Emoji Sequence ========================

    /**
     * Show a sequence of contextual emojis from response_meta.
     * Each emoji is shown for `durationPerEmoji` ms with the specified transition.
     */
    private fun showEmojiSequence(expression: ExpressionData) {
        emojiSequenceJob?.cancel()
        emojiSequenceJob = scope.launch {
            for (emojiCode in expression.emojis) {
                Log.d(TAG, "Showing contextual emoji: $emojiCode (${expression.transition})")
                // Update the emoji via ExpressionManager
                // We use the hex code directly — ExpressionManager needs to support this
                StateManager.updateEmotion(emojiCode)
                delay(expression.durationPerEmoji)
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



