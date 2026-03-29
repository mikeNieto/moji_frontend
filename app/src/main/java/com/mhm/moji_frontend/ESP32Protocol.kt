package com.mhm.moji_frontend

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mhm.moji_frontend.data.ActionData

/**
 * Pure JSON serializer for the BLE contract implemented by the ESP32 firmware.
 * Keeps legacy backend metadata out of the payload actually sent over BLE.
 */
object Esp32CommandSerializer {
    fun heartbeat(timestampMs: Long): String = JsonObject().apply {
        addProperty("type", "heartbeat")
        addProperty("timestamp", timestampMs)
    }.toString()

    fun stop(): String = JsonObject().apply {
        addProperty("type", "stop")
    }.toString()

    fun turnRight(degrees: Int): String = turnRightStep(degrees).toString()

    fun turnLeft(degrees: Int): String = turnLeftStep(degrees).toString()

    fun moveForwardCm(cm: Int): String = moveForwardCmStep(cm).toString()

    fun moveBackwardCm(cm: Int): String = moveBackwardCmStep(cm).toString()

    fun moveForwardDuration(durationMs: Long): String = moveForwardDurationStep(durationMs).toString()

    fun moveBackwardDuration(durationMs: Long): String = moveBackwardDurationStep(durationMs).toString()

    fun ledColor(r: Int, g: Int, b: Int, durationMs: Long): String = ledColorStep(r, g, b, durationMs).toString()

    fun moveSequence(steps: List<JsonObject>): String {
        val stepsArray = JsonArray()
        steps.forEach { stepsArray.add(it) }

        return JsonObject().apply {
            addProperty("type", "move_sequence")
            add("steps", stepsArray)
        }.toString()
    }

    fun turnRightStep(degrees: Int): JsonObject = JsonObject().apply {
        addProperty("type", "turn_right_deg")
        addProperty("degrees", degrees.coerceAtLeast(0))
    }

    fun turnLeftStep(degrees: Int): JsonObject = JsonObject().apply {
        addProperty("type", "turn_left_deg")
        addProperty("degrees", degrees.coerceAtLeast(0))
    }

    fun moveForwardCmStep(cm: Int): JsonObject = JsonObject().apply {
        addProperty("type", "move_forward_cm")
        addProperty("cm", cm.coerceAtLeast(0))
    }

    fun moveBackwardCmStep(cm: Int): JsonObject = JsonObject().apply {
        addProperty("type", "move_backward_cm")
        addProperty("cm", cm.coerceAtLeast(0))
    }

    fun moveForwardDurationStep(durationMs: Long): JsonObject = JsonObject().apply {
        addProperty("type", "move_forward_duration")
        addProperty("duration_ms", durationMs.coerceAtLeast(0L))
    }

    fun moveBackwardDurationStep(durationMs: Long): JsonObject = JsonObject().apply {
        addProperty("type", "move_backward_duration")
        addProperty("duration_ms", durationMs.coerceAtLeast(0L))
    }

    fun ledColorStep(r: Int, g: Int, b: Int, durationMs: Long): JsonObject = JsonObject().apply {
        addProperty("type", "led_color")
        addProperty("r", r.coerceIn(0, 255))
        addProperty("g", g.coerceIn(0, 255))
        addProperty("b", b.coerceIn(0, 255))
        addProperty("duration_ms", durationMs.coerceAtLeast(0L))
    }
}

/**
 * ESP32Protocol: Serializes robot commands to JSON UTF-8 and sends them via BleManager.
 *
 * Handles:
 * - Primitive movement commands (turn, move by cm, move by duration, led_color, stop)
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
        return bleManager.sendJson(Esp32CommandSerializer.heartbeat(timestampMs))
    }

    fun sendStop(): Boolean {
        Log.d(TAG, "Sending STOP to ESP32")
        return bleManager.sendJson(Esp32CommandSerializer.stop())
    }

    fun sendTurnRight(degrees: Int): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.turnRight(degrees))
    }

    fun sendTurnLeft(degrees: Int): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.turnLeft(degrees))
    }

    fun sendMoveForward(cm: Int): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.moveForwardCm(cm))
    }

    fun sendMoveBackward(cm: Int): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.moveBackwardCm(cm))
    }

    fun sendMoveForwardDuration(durationMs: Long): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.moveForwardDuration(durationMs))
    }

    fun sendMoveBackwardDuration(durationMs: Long): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.moveBackwardDuration(durationMs))
    }

    fun sendLedColor(r: Int, g: Int, b: Int, durationMs: Long): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.ledColor(r, g, b, durationMs))
    }

    /**
     * Send a move_sequence with a list of step commands.
     * Steps must already be compiled to primitives supported by the ESP32 firmware.
     */
    fun sendMoveSequence(steps: List<JsonObject>): Boolean {
        return bleManager.sendJson(Esp32CommandSerializer.moveSequence(steps))
    }

    /**
     * Execute a face-scan rotation sequence — used when SEARCHING for a person.
     * Rotates right 90°, then left 180° to cover the area.
     */
    fun sendFaceScanSequence(): Boolean {
        val steps = listOf(
            Esp32CommandSerializer.turnRightStep(90),
            Esp32CommandSerializer.turnLeftStep(180)
        )
        Log.d(TAG, "Sending face scan sequence to ESP32")
        return sendMoveSequence(steps = steps)
    }

    /**
     * Process a list of ActionData from ResponseMeta or FaceScanActions
     * and send appropriate BLE commands.
     */
    fun executeActions(actions: List<ActionData>) {
        for (action in actions) {
            executeAction(action)
        }
    }

    private fun executeAction(action: ActionData) {
        Log.d(
            TAG,
            "Executing action: type=${action.type}, degrees=${action.degrees}, durationMs=${action.durationMs}, " +
                "cm=${action.cm}, r=${action.r}, g=${action.g}, b=${action.b}, steps=${action.steps?.size ?: 0}"
        )

        when (action.type) {
            "turn_right_deg" -> sendTurnRight(
                degrees = action.degrees ?: 90
            )
            "turn_left_deg" -> sendTurnLeft(
                degrees = action.degrees ?: 90
            )
            "move_forward_cm" -> sendMoveForward(
                cm = action.cm ?: 20
            )
            "move_backward_cm" -> sendMoveBackward(
                cm = action.cm ?: 20
            )
            "move_forward_duration" -> sendMoveForwardDuration(
                durationMs = action.durationMs ?: 1500L
            )
            "move_backward_duration" -> sendMoveBackwardDuration(
                durationMs = action.durationMs ?: 1500L
            )
            "led_color" -> sendLedColor(
                r = action.r ?: 0,
                g = action.g ?: 0,
                b = action.b ?: 0,
                durationMs = action.durationMs ?: 1000L
            )
            "stop" -> sendStop()

            "move_sequence" -> {
                val compiledSteps = (action.steps ?: emptyList()).mapNotNull { step ->
                    compilePrimitiveToJson(step)
                }
                if (compiledSteps.isEmpty()) {
                    Log.w(TAG, "move_sequence received without valid primitive steps — skipping")
                } else {
                    sendMoveSequence(steps = compiledSteps)
                }
            }

            // ────── Semantic aliases: compiled locally, never forwarded as-is ──────
            "wave" -> {
                Log.d(TAG, "Compiling 'wave' alias → move_sequence")
                val steps = listOf(
                    Esp32CommandSerializer.turnRightStep(20),
                    Esp32CommandSerializer.turnLeftStep(40),
                    Esp32CommandSerializer.turnRightStep(20)
                )
                sendMoveSequence(steps = steps)
            }

            "nod" -> {
                Log.d(TAG, "Compiling 'nod' alias → led_color (visual nod — no tilt axis)")
                sendLedColor(r = 0, g = 200, b = 100, durationMs = 600)
            }

            "shake_head" -> {
                Log.d(TAG, "Compiling 'shake_head' alias → move_sequence")
                val steps = listOf(
                    Esp32CommandSerializer.turnLeftStep(30),
                    Esp32CommandSerializer.turnRightStep(60),
                    Esp32CommandSerializer.turnLeftStep(30)
                )
                sendMoveSequence(steps = steps)
            }

            else -> Log.w(TAG, "Unknown action type '${action.type}' — ignoring")
        }
    }

    /**
     * Compile an ActionData primitive step to a JSONObject.
     * Returns null if the type is not a known primitive supported by the ESP32.
     */
    private fun compilePrimitiveToJson(action: ActionData): JsonObject? {
        return when (action.type) {
            "turn_right_deg" -> Esp32CommandSerializer.turnRightStep(action.degrees ?: 90)
            "turn_left_deg" -> Esp32CommandSerializer.turnLeftStep(action.degrees ?: 90)
            "move_forward_cm" -> Esp32CommandSerializer.moveForwardCmStep(action.cm ?: 20)
            "move_backward_cm" -> Esp32CommandSerializer.moveBackwardCmStep(action.cm ?: 20)
            "move_forward_duration" -> Esp32CommandSerializer.moveForwardDurationStep(action.durationMs ?: 1500L)
            "move_backward_duration" -> Esp32CommandSerializer.moveBackwardDurationStep(action.durationMs ?: 1500L)
            "led_color" -> Esp32CommandSerializer.ledColorStep(
                r = action.r ?: 0,
                g = action.g ?: 0,
                b = action.b ?: 0,
                durationMs = action.durationMs ?: 1000L
            )
            "stop" -> JsonObject().apply {
                addProperty("type", "stop")
            }
            else -> {
                Log.w(TAG, "Cannot compile unsupported primitive '${action.type}' in move_sequence step — skipping")
                null
            }
        }
    }
}
