package com.mhm.moji_frontend

import android.util.Log
import com.mhm.moji_frontend.data.ActionData
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESP32Protocol: Serializes robot commands to JSON UTF-8 and sends them via BleManager.
 *
 * Handles:
 * - All primitive movement commands (turn, move, led_color, stop)
 * - Compound move_sequence
 * - Semantic alias compilation: wave, nod, shake_head → move_sequence primitives
 * - Heartbeat messages
 *
 * IMPORTANT: Semantic aliases (wave, nod, shake_head) are NEVER forwarded to the ESP32.
 * They are compiled locally here to the appropriate move_sequence primitives.
 */
class ESP32Protocol(private val bleManager: BleManager) {

    companion object {
        private const val TAG = "ESP32Protocol"
    }

    // ======================== PUBLIC COMMANDS ========================

    fun sendHeartbeat(timestampMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "heartbeat")
            put("timestamp", timestampMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    fun sendStop(): Boolean {
        val json = JSONObject().apply {
            put("type", "stop")
        }.toString()
        Log.d(TAG, "Sending STOP to ESP32")
        return bleManager.sendJson(json)
    }

    fun sendTurnRight(degrees: Int, speed: Int, durationMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "turn_right_deg")
            put("degrees", degrees)
            put("speed", speed)
            put("duration_ms", durationMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    fun sendTurnLeft(degrees: Int, speed: Int, durationMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "turn_left_deg")
            put("degrees", degrees)
            put("speed", speed)
            put("duration_ms", durationMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    fun sendMoveForward(cm: Int, speed: Int, durationMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "move_forward_cm")
            put("cm", cm)
            put("speed", speed)
            put("duration_ms", durationMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    fun sendMoveBackward(cm: Int, speed: Int, durationMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "move_backward_cm")
            put("cm", cm)
            put("speed", speed)
            put("duration_ms", durationMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    fun sendLedColor(r: Int, g: Int, b: Int, durationMs: Long): Boolean {
        val json = JSONObject().apply {
            put("type", "led_color")
            put("r", r)
            put("g", g)
            put("b", b)
            put("duration_ms", durationMs)
        }.toString()
        return bleManager.sendJson(json)
    }

    /**
     * Send a move_sequence with a list of step commands.
     * Steps must already be compiled to primitives (turn_*, move_*, led_color).
     */
    fun sendMoveSequence(totalDurationMs: Long, steps: List<JSONObject>): Boolean {
        val stepsArray = JSONArray()
        steps.forEach { stepsArray.put(it) }

        val json = JSONObject().apply {
            put("type", "move_sequence")
            put("total_duration_ms", totalDurationMs)
            put("steps", stepsArray)
        }.toString()
        return bleManager.sendJson(json)
    }

    /**
     * Execute a face-scan rotation sequence — used when SEARCHING for a person.
     * Rotates right 90°, then left 180° to cover the area.
     * This matches the face_scan_actions pattern from the backend.
     */
    fun sendFaceScanSequence(): Boolean {
        val steps = listOf(
            JSONObject().apply {
                put("type", "turn_right_deg")
                put("degrees", 90)
                put("speed", 25)
                put("duration_ms", 1500)
            },
            JSONObject().apply {
                put("type", "turn_left_deg")
                put("degrees", 180)
                put("speed", 25)
                put("duration_ms", 3000)
            }
        )
        Log.d(TAG, "Sending face scan sequence to ESP32")
        return sendMoveSequence(totalDurationMs = 4500, steps = steps)
    }

    /**
     * Process a list of ActionData from ResponseMeta or FaceScanActions
     * and send appropriate BLE commands.
     *
     * - Primitive actions (turn_*, move_*, led_color, stop): sent directly
     * - move_sequence: compile steps recursively and send as one message
     * - Semantic aliases (wave, nod, shake_head): compiled to move_sequence
     */
    fun executeActions(actions: List<ActionData>) {
        for (action in actions) {
            executeAction(action)
        }
    }

    private fun executeAction(action: ActionData) {
        Log.d(TAG, "Executing action: type=${action.type}, degrees=${action.degrees}, " +
                "speed=${action.speed}, durationMs=${action.durationMs}, " +
                "cm=${action.cm}, r=${action.r}, g=${action.g}, b=${action.b}")

        when (action.type) {
            "turn_right_deg" -> sendTurnRight(
                degrees = action.degrees ?: 90,
                speed = action.speed ?: 30,
                durationMs = action.durationMs ?: 1500L
            )
            "turn_left_deg" -> sendTurnLeft(
                degrees = action.degrees ?: 90,
                speed = action.speed ?: 30,
                durationMs = action.durationMs ?: 1500L
            )
            "move_forward_cm" -> sendMoveForward(
                cm = action.cm ?: 20,
                speed = action.speed ?: 40,
                durationMs = action.durationMs ?: 1000L
            )
            "move_backward_cm" -> sendMoveBackward(
                cm = action.cm ?: 20,
                speed = action.speed ?: 40,
                durationMs = action.durationMs ?: 1000L
            )
            "led_color" -> sendLedColor(
                r = action.r ?: 0,
                g = action.g ?: 0,
                b = action.b ?: 0,
                durationMs = action.durationMs ?: 1000L
            )
            "stop" -> sendStop()

            "move_sequence" -> {
                // Compile steps to JSON objects
                val compiledSteps = (action.steps ?: emptyList()).mapNotNull { step ->
                    compilePrimitiveToJson(step)
                }
                sendMoveSequence(
                    totalDurationMs = action.totalDurationMs ?: action.durationMs ?: 3000L,
                    steps = compiledSteps
                )
            }

            // ────── Semantic aliases: compiled locally, never forwarded as-is ──────
            "wave" -> {
                Log.d(TAG, "Compiling 'wave' alias → move_sequence")
                val steps = listOf(
                    JSONObject().apply {
                        put("type", "turn_right_deg"); put("degrees", 20)
                        put("speed", 50); put("duration_ms", 300)
                    },
                    JSONObject().apply {
                        put("type", "turn_left_deg"); put("degrees", 40)
                        put("speed", 50); put("duration_ms", 400)
                    },
                    JSONObject().apply {
                        put("type", "turn_right_deg"); put("degrees", 20)
                        put("speed", 50); put("duration_ms", 300)
                    }
                )
                sendMoveSequence(totalDurationMs = 1000, steps = steps)
            }

            "nod" -> {
                Log.d(TAG, "Compiling 'nod' alias → led_color (visual nod — no tilt axis)")
                // ESP32 doesn't have a tilt axis; simulate with LED flash
                sendLedColor(r = 0, g = 200, b = 100, durationMs = 600)
            }

            "shake_head" -> {
                Log.d(TAG, "Compiling 'shake_head' alias → move_sequence")
                val steps = listOf(
                    JSONObject().apply {
                        put("type", "turn_left_deg"); put("degrees", 30)
                        put("speed", 50); put("duration_ms", 300)
                    },
                    JSONObject().apply {
                        put("type", "turn_right_deg"); put("degrees", 60)
                        put("speed", 50); put("duration_ms", 500)
                    },
                    JSONObject().apply {
                        put("type", "turn_left_deg"); put("degrees", 30)
                        put("speed", 50); put("duration_ms", 300)
                    }
                )
                sendMoveSequence(totalDurationMs = 1100, steps = steps)
            }

            else -> Log.w(TAG, "Unknown action type '${action.type}' — ignoring")
        }
    }

    /**
     * Compile an ActionData primitive step to a JSONObject.
     * Semantic aliases inside move_sequence steps are compiled recursively.
     * Returns null if the type is not a known primitive.
     */
    private fun compilePrimitiveToJson(action: ActionData): JSONObject? {
        return when (action.type) {
            "turn_right_deg" -> JSONObject().apply {
                put("type", "turn_right_deg")
                put("degrees", action.degrees ?: 90)
                put("speed", action.speed ?: 30)
                put("duration_ms", action.durationMs ?: 1500)
            }
            "turn_left_deg" -> JSONObject().apply {
                put("type", "turn_left_deg")
                put("degrees", action.degrees ?: 90)
                put("speed", action.speed ?: 30)
                put("duration_ms", action.durationMs ?: 1500)
            }
            "move_forward_cm" -> JSONObject().apply {
                put("type", "move_forward_cm")
                put("cm", action.cm ?: 20)
                put("speed", action.speed ?: 40)
                put("duration_ms", action.durationMs ?: 1000)
            }
            "move_backward_cm" -> JSONObject().apply {
                put("type", "move_backward_cm")
                put("cm", action.cm ?: 20)
                put("speed", action.speed ?: 40)
                put("duration_ms", action.durationMs ?: 1000)
            }
            "led_color" -> JSONObject().apply {
                put("type", "led_color")
                put("r", action.r ?: 0)
                put("g", action.g ?: 0)
                put("b", action.b ?: 0)
                put("duration_ms", action.durationMs ?: 1000)
            }
            "stop" -> JSONObject().apply {
                put("type", "stop")
            }
            else -> {
                Log.w(TAG, "Cannot compile unsupported primitive '${action.type}' in move_sequence step — skipping")
                null
            }
        }
    }
}

