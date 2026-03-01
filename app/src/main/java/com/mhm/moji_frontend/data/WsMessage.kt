package com.mhm.moji_frontend.data

/**
 * Sealed class hierarchy for all WebSocket messages.
 * Outgoing = Android → Backend
 * Incoming = Backend → Android
 */

// ======================== OUTGOING MESSAGES ========================

sealed class WsOutgoing {

    /** First message after connection — always sent first */
    data class Auth(
        val apiKey: String,
        val deviceId: String
    ) : WsOutgoing()

    /** Start a new interaction (after face recognition) */
    data class InteractionStart(
        val requestId: String,
        val personId: String,          // "unknown" if not recognized
        val faceRecognized: Boolean,
        val faceConfidence: Float,
        val faceEmbedding: String?,    // base64 of 128D float array, only when unknown
        val batteryRobot: Int,
        val batteryPhone: Int,
        val sensors: Map<String, Any> = emptyMap()
    ) : WsOutgoing()

    /** End of audio stream */
    data class AudioEnd(
        val requestId: String
    ) : WsOutgoing()

    /** Image captured (response to capture_request) */
    data class Image(
        val requestId: String,
        val purpose: String,
        val data: String               // base64 JPEG
    ) : WsOutgoing()

    /** Video captured (response to capture_request) */
    data class Video(
        val requestId: String,
        val durationMs: Long,
        val data: String               // base64 MP4
    ) : WsOutgoing()

    /** Text message (alternative to audio, mainly for tests) */
    data class Text(
        val requestId: String,
        val content: String,
        val personId: String
    ) : WsOutgoing()

    /** Face scan mode active */
    data class FaceScanMode(
        val requestId: String
    ) : WsOutgoing()

    /** Person detected by camera */
    data class PersonDetected(
        val requestId: String,
        val known: Boolean,
        val personId: String?,
        val confidence: Float,
        val faceEmbedding: String       // base64 of 128D float vector
    ) : WsOutgoing()

    /** Battery alert */
    data class BatteryAlert(
        val requestId: String,
        val batteryLevel: Int,
        val source: String              // "phone" or "robot"
    ) : WsOutgoing()
}

// ======================== INCOMING MESSAGES ========================

sealed class WsIncoming {

    /** Authentication confirmed */
    data class AuthOk(
        val sessionId: String
    ) : WsIncoming()

    /** New person registered by backend */
    data class PersonRegistered(
        val personId: String,
        val name: String
    ) : WsIncoming()

    /** Emotion tag — FIRST message of each interaction response */
    data class EmotionMessage(
        val requestId: String,
        val emotion: String,
        val personIdentified: String?,
        val confidence: Float
    ) : WsIncoming()

    /** Text chunk — streaming text fragments */
    data class TextChunk(
        val requestId: String,
        val text: String
    ) : WsIncoming()

    /** Capture request — backend wants a photo or video */
    data class CaptureRequest(
        val requestId: String,
        val captureType: String,        // "photo" or "video"
        val durationMs: Long?
    ) : WsIncoming()

    /** Response metadata with expression emojis and ESP32 actions */
    data class ResponseMeta(
        val requestId: String,
        val responseText: String,
        val personName: String?,
        val expression: ExpressionData?,
        val actions: List<ActionData>
    ) : WsIncoming()

    /** Face scan actions (ESP32 should rotate searching for faces) */
    data class FaceScanActions(
        val requestId: String,
        val actions: List<ActionData>
    ) : WsIncoming()

    /** Stream end — interaction fully processed */
    data class StreamEnd(
        val requestId: String,
        val processingTimeMs: Long
    ) : WsIncoming()

    /** Error message */
    data class ErrorMessage(
        val requestId: String?,
        val errorCode: String,
        val message: String,
        val recoverable: Boolean
    ) : WsIncoming()

    /** Unknown message type */
    data class Unknown(
        val type: String,
        val rawJson: String
    ) : WsIncoming()
}

// ======================== SUPPORTING DATA CLASSES ========================

data class ExpressionData(
    val emojis: List<String>,
    val durationPerEmoji: Long,
    val transition: String              // "fade", "bounce", "slide"
)

data class ActionData(
    val type: String,
    val degrees: Int? = null,
    val speed: Int? = null,
    val durationMs: Long? = null,
    val cm: Int? = null,
    val r: Int? = null,
    val g: Int? = null,
    val b: Int? = null,
    val totalDurationMs: Long? = null,
    val emotionDuring: String? = null,
    val steps: List<ActionData>? = null
)

