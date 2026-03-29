package com.mhm.moji_frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryDebugFormatterTest {

    @Test
    fun fromJson_flattensNestedTelemetryIntoRows() {
        val snapshot = TelemetryDebugFormatter.fromJson(
            """
            {
              "type": "telemetry",
              "timestamp": 191020,
              "battery": {
                "pack_voltage": 7.527077675,
                "adc_mv": 2380,
                "percentage": 63,
                "sensor_ok": true
              },
              "sensors": {
                "distance_front": 7.254449844,
                "distance_rear": 7.511699677
              },
              "motors": {
                "state": "stop"
              }
            }
            """.trimIndent(),
            receivedAtMillis = 1234L
        )

        assertEquals("telemetry", snapshot.payloadType)
        assertEquals(1234L, snapshot.updatedAtMillis)
        assertNull(snapshot.parseError)
        assertEquals("191020", snapshot.rows.first { it.path == "timestamp" }.value)
        assertEquals("7.527077675", snapshot.rows.first { it.path == "battery.pack_voltage" }.value)
        assertEquals("63", snapshot.rows.first { it.path == "battery.percentage" }.value)
        assertEquals("true", snapshot.rows.first { it.path == "battery.sensor_ok" }.value)
        assertEquals("stop", snapshot.rows.first { it.path == "motors.state" }.value)
    }

    @Test
    fun fromJson_supportsArraysNullsAndEmptyContainers() {
        val snapshot = TelemetryDebugFormatter.fromJson(
            """
            {
              "type": "telemetry",
              "items": [1, {"ok": false}, null],
              "empty_array": [],
              "empty_object": {}
            }
            """.trimIndent()
        )

        assertEquals("1", snapshot.rows.first { it.path == "items[0]" }.value)
        assertEquals("false", snapshot.rows.first { it.path == "items[1].ok" }.value)
        assertEquals("null", snapshot.rows.first { it.path == "items[2]" }.value)
        assertEquals("[]", snapshot.rows.first { it.path == "empty_array" }.value)
        assertEquals("{}", snapshot.rows.first { it.path == "empty_object" }.value)
    }

    @Test
    fun fromJson_returnsParseErrorForInvalidJson() {
        val snapshot = TelemetryDebugFormatter.fromJson("{oops")

        assertEquals("json_invalido", snapshot.payloadType)
        assertTrue(snapshot.parseError?.isNotBlank() == true)
        assertTrue(snapshot.rows.isEmpty())
    }
}

