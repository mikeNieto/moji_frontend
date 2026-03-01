package com.mhm.moji_frontend.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * WsMessageParser: Parses incoming JSON messages from the backend WebSocket
 * into strongly-typed sealed classes.
 *
 * Also serializes outgoing messages to JSON strings.
 */
object WsMessageParser {

    private const val TAG = "WsMessageParser"
    private val gson = Gson()

    // ======================== PARSING (Incoming) ========================

    fun parseIncoming(json: String): WsIncoming {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val type = obj.get("type")?.asString ?: return WsIncoming.Unknown("no_type", json)

            when (type) {
                "auth_ok" -> parseAuthOk(obj)
                "person_registered" -> parsePersonRegistered(obj)
                "emotion" -> parseEmotion(obj)
                "text_chunk" -> parseTextChunk(obj)
                "capture_request" -> parseCaptureRequest(obj)
                "response_meta" -> parseResponseMeta(obj)
                "face_scan_actions" -> parseFaceScanActions(obj)
                "stream_end" -> parseStreamEnd(obj)
                "error" -> parseError(obj)
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                    WsIncoming.Unknown(type, json)
                }
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
            WsIncoming.Unknown("parse_error", json)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing message: ${e.message}", e)
            WsIncoming.Unknown("error", json)
        }
    }

    private fun parseAuthOk(obj: JsonObject): WsIncoming.AuthOk {
        return WsIncoming.AuthOk(
            sessionId = obj.get("session_id")?.asString ?: ""
        )
    }

    private fun parsePersonRegistered(obj: JsonObject): WsIncoming.PersonRegistered {
        return WsIncoming.PersonRegistered(
            personId = obj.get("person_id")?.asString ?: "",
            name = obj.get("name")?.asString ?: ""
        )
    }

    private fun parseEmotion(obj: JsonObject): WsIncoming.EmotionMessage {
        return WsIncoming.EmotionMessage(
            requestId = obj.get("request_id")?.asString ?: "",
            emotion = obj.get("emotion")?.asString ?: "neutral",
            personIdentified = obj.get("person_identified")?.asString,
            confidence = obj.get("confidence")?.asFloat ?: 0f
        )
    }

    private fun parseTextChunk(obj: JsonObject): WsIncoming.TextChunk {
        return WsIncoming.TextChunk(
            requestId = obj.get("request_id")?.asString ?: "",
            text = obj.get("text")?.asString ?: ""
        )
    }

    private fun parseCaptureRequest(obj: JsonObject): WsIncoming.CaptureRequest {
        return WsIncoming.CaptureRequest(
            requestId = obj.get("request_id")?.asString ?: "",
            captureType = obj.get("capture_type")?.asString ?: "photo",
            durationMs = if (obj.has("duration_ms") && !obj.get("duration_ms").isJsonNull)
                obj.get("duration_ms").asLong else null
        )
    }

    private fun parseResponseMeta(obj: JsonObject): WsIncoming.ResponseMeta {
        val expressionObj = if (obj.has("expression") && !obj.get("expression").isJsonNull)
            obj.getAsJsonObject("expression") else null

        val expression = expressionObj?.let {
            ExpressionData(
                emojis = it.getAsJsonArray("emojis")?.map { e -> e.asString } ?: emptyList(),
                durationPerEmoji = it.get("duration_per_emoji")?.asLong ?: 2000L,
                transition = it.get("transition")?.asString ?: "fade"
            )
        }

        val actions = if (obj.has("actions") && obj.get("actions").isJsonArray) {
            obj.getAsJsonArray("actions").map { parseAction(it.asJsonObject) }
        } else {
            emptyList()
        }

        return WsIncoming.ResponseMeta(
            requestId = obj.get("request_id")?.asString ?: "",
            responseText = obj.get("response_text")?.asString ?: "",
            personName = if (obj.has("person_name") && !obj.get("person_name").isJsonNull)
                obj.get("person_name").asString else null,
            expression = expression,
            actions = actions
        )
    }

    private fun parseAction(obj: JsonObject): ActionData {
        val steps = if (obj.has("steps") && obj.get("steps").isJsonArray) {
            obj.getAsJsonArray("steps").map { parseAction(it.asJsonObject) }
        } else null

        return ActionData(
            type = obj.get("type")?.asString ?: "",
            degrees = if (obj.has("degrees")) obj.get("degrees")?.asInt else null,
            speed = if (obj.has("speed")) obj.get("speed")?.asInt else null,
            durationMs = if (obj.has("duration_ms")) obj.get("duration_ms")?.asLong else null,
            cm = if (obj.has("cm")) obj.get("cm")?.asInt else null,
            r = if (obj.has("r")) obj.get("r")?.asInt else null,
            g = if (obj.has("g")) obj.get("g")?.asInt else null,
            b = if (obj.has("b")) obj.get("b")?.asInt else null,
            totalDurationMs = if (obj.has("total_duration_ms")) obj.get("total_duration_ms")?.asLong else null,
            emotionDuring = if (obj.has("emotion_during")) obj.get("emotion_during")?.asString else null,
            steps = steps
        )
    }

    private fun parseFaceScanActions(obj: JsonObject): WsIncoming.FaceScanActions {
        val actions = if (obj.has("actions") && obj.get("actions").isJsonArray) {
            obj.getAsJsonArray("actions").map { parseAction(it.asJsonObject) }
        } else {
            emptyList()
        }
        return WsIncoming.FaceScanActions(
            requestId = obj.get("request_id")?.asString ?: "",
            actions = actions
        )
    }

    private fun parseStreamEnd(obj: JsonObject): WsIncoming.StreamEnd {
        return WsIncoming.StreamEnd(
            requestId = obj.get("request_id")?.asString ?: "",
            processingTimeMs = obj.get("processing_time_ms")?.asLong ?: 0L
        )
    }

    private fun parseError(obj: JsonObject): WsIncoming.ErrorMessage {
        return WsIncoming.ErrorMessage(
            requestId = if (obj.has("request_id") && !obj.get("request_id").isJsonNull)
                obj.get("request_id").asString else null,
            errorCode = obj.get("error_code")?.asString ?: "UNKNOWN",
            message = obj.get("message")?.asString ?: "Unknown error",
            recoverable = obj.get("recoverable")?.asBoolean ?: true
        )
    }

    // ======================== SERIALIZATION (Outgoing) ========================

    fun serializeOutgoing(msg: WsOutgoing): String {
        return when (msg) {
            is WsOutgoing.Auth -> gson.toJson(mapOf(
                "type" to "auth",
                "api_key" to msg.apiKey,
                "device_id" to msg.deviceId
            ))
            is WsOutgoing.InteractionStart -> {
                val map = mutableMapOf<String, Any?>(
                    "type" to "interaction_start",
                    "request_id" to msg.requestId,
                    "person_id" to msg.personId,
                    "face_recognized" to msg.faceRecognized,
                    "face_confidence" to msg.faceConfidence,
                    "face_embedding" to msg.faceEmbedding,
                    "context" to mapOf(
                        "battery_robot" to msg.batteryRobot,
                        "battery_phone" to msg.batteryPhone,
                        "sensors" to msg.sensors
                    )
                )
                gson.toJson(map)
            }
            is WsOutgoing.AudioEnd -> gson.toJson(mapOf(
                "type" to "audio_end",
                "request_id" to msg.requestId
            ))
            is WsOutgoing.Image -> gson.toJson(mapOf(
                "type" to "image",
                "request_id" to msg.requestId,
                "purpose" to msg.purpose,
                "data" to msg.data
            ))
            is WsOutgoing.Video -> gson.toJson(mapOf(
                "type" to "video",
                "request_id" to msg.requestId,
                "duration_ms" to msg.durationMs,
                "data" to msg.data
            ))
            is WsOutgoing.Text -> gson.toJson(mapOf(
                "type" to "text",
                "request_id" to msg.requestId,
                "content" to msg.content,
                "person_id" to msg.personId
            ))
            is WsOutgoing.FaceScanMode -> gson.toJson(mapOf(
                "type" to "face_scan_mode",
                "request_id" to msg.requestId
            ))
            is WsOutgoing.PersonDetected -> gson.toJson(mapOf(
                "type" to "person_detected",
                "request_id" to msg.requestId,
                "known" to msg.known,
                "person_id" to msg.personId,
                "confidence" to msg.confidence,
                "face_embedding" to msg.faceEmbedding
            ))
            is WsOutgoing.BatteryAlert -> gson.toJson(mapOf(
                "type" to "battery_alert",
                "request_id" to msg.requestId,
                "battery_level" to msg.batteryLevel,
                "source" to msg.source
            ))
        }
    }
}

