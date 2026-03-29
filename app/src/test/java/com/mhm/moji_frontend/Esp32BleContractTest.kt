package com.mhm.moji_frontend

import com.google.gson.JsonParser
import com.mhm.moji_frontend.data.WsIncoming
import com.mhm.moji_frontend.data.WsMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32BleContractTest {

    @Test
    fun serializer_usesNewTurnContract_withoutLegacyFields() {
        val json = JsonParser.parseString(Esp32CommandSerializer.turnRight(90)).asJsonObject

        assertEquals("turn_right_deg", json.get("type").asString)
        assertEquals(90, json.get("degrees").asInt)
        assertFalse(json.has("speed"))
        assertFalse(json.has("duration_ms"))
    }

    @Test
    fun serializer_supportsDurationMoves_andSequenceWithoutTotalDuration() {
        val move = JsonParser.parseString(Esp32CommandSerializer.moveForwardDuration(1500)).asJsonObject
        assertEquals("move_forward_duration", move.get("type").asString)
        assertEquals(1500L, move.get("duration_ms").asLong)
        assertFalse(move.has("cm"))
        assertFalse(move.has("speed"))

        val sequence = JsonParser.parseString(
            Esp32CommandSerializer.moveSequence(
                listOf(
                    Esp32CommandSerializer.turnLeftStep(45),
                    Esp32CommandSerializer.moveForwardDurationStep(900)
                )
            )
        ).asJsonObject

        assertEquals("move_sequence", sequence.get("type").asString)
        assertFalse(sequence.has("total_duration_ms"))
        val steps = sequence.getAsJsonArray("steps")
        assertEquals(2, steps.size())
        assertEquals("turn_left_deg", steps[0].asJsonObject.get("type").asString)
        assertEquals("move_forward_duration", steps[1].asJsonObject.get("type").asString)
    }

    @Test
    fun wsMessageParser_readsNewDurationAndNestedSequenceActions() {
        val parsed = WsMessageParser.parseIncoming(
            """
            {
              "type": "response_meta",
              "request_id": "req-1",
              "response_text": "hola",
              "actions": [
                {"type": "move_forward_duration", "duration_ms": 1200},
                {
                  "type": "move_sequence",
                  "steps": [
                    {"type": "turn_left_deg", "degrees": 90},
                    {"type": "move_backward_cm", "cm": 15},
                    {"type": "led_color", "r": 255, "g": 140, "b": 0, "duration_ms": 600}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(parsed is WsIncoming.ResponseMeta)
        parsed as WsIncoming.ResponseMeta

        assertEquals(2, parsed.actions.size)
        assertEquals("move_forward_duration", parsed.actions[0].type)
        assertEquals(1200L, parsed.actions[0].durationMs)

        val sequence = parsed.actions[1]
        assertEquals("move_sequence", sequence.type)
        assertNotNull(sequence.steps)
        assertEquals(3, sequence.steps?.size)
        assertEquals("turn_left_deg", sequence.steps?.get(0)?.type)
        assertEquals(90, sequence.steps?.get(0)?.degrees)
        assertEquals("move_backward_cm", sequence.steps?.get(1)?.type)
        assertEquals(15, sequence.steps?.get(1)?.cm)
    }

    @Test
    fun telemetryParser_parsesExpandedTelemetryFields() {
        val parsed = TelemetryParser.parse(
            """
            {
              "type": "telemetry",
              "timestamp": 1234567890,
              "battery": {
                "bus_voltage": 7.18,
                "load_voltage": 7.21,
                "shunt_voltage_mv": 28.0,
                "current_ma": 410.5,
                "power_mw": 2959.7,
                "percentage": 75,
                "sensor_ok": true
              },
              "sensors": {
                "distance_front": 150,
                "distance_rear": 200,
                "cliff_front_left": 62,
                "cliff_front_right": 61,
                "cliff_rear": 60
              },
              "motors": {
                "state": "stop",
                "last_action": "move_forward_duration"
              },
              "leds": {"mode": "idle"},
              "heartbeat": {"brain_online": true},
              "safety": {
                "cliff_active": false,
                "obstacle_blocked": true
              },
              "uptime": 3600
            }
            """.trimIndent()
        )

        assertTrue(parsed is TelemetryParser.Esp32Message.TelemetryData)
        parsed as TelemetryParser.Esp32Message.TelemetryData

        val telemetry = parsed.telemetry
        assertEquals(1234567890L, telemetry.timestamp)
        assertEquals(3600L, telemetry.uptime)
        assertEquals(75, telemetry.battery?.percentage)
        assertEquals(7.21, telemetry.battery?.loadVoltage ?: 0.0, 0.0)
        assertEquals(2959.7, telemetry.battery?.powerMw ?: 0.0, 0.0)
        assertEquals("stop", telemetry.motors?.state)
        assertEquals("move_forward_duration", telemetry.motors?.lastAction)
        assertEquals("idle", telemetry.leds?.mode)
        assertTrue(telemetry.heartbeat?.brainOnline == true)
        assertTrue(telemetry.safety?.obstacleBlocked == true)
    }
}
