package com.mhm.moji_frontend

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * TelemetryParser: Parses JSON messages received from the ESP32 via BLE RX notifications.
 *
 * Handles two message types:
 *   - "telemetry": periodic sensor + battery data (every ~1s)
 *   - command confirmation: {"status": "ok", "command_id": "...", "error_msg": ""}
 */
object TelemetryParser {

    private const val TAG = "TelemetryParser"

    data class BatteryTelemetry(
        val percentage: Int,
        val busVoltage: Double,
        val currentMa: Double,
        val sensorOk: Boolean
    )

    data class SensorTelemetry(
        val distanceFront: Int,
        val distanceRear: Int,
        val cliffFrontLeft: Int,
        val cliffFrontRight: Int,
        val cliffRear: Int
    )

    data class SafetyTelemetry(
        val cliffActive: Boolean,
        val obstacleBlocked: Boolean
    )

    data class Telemetry(
        val timestamp: Long,
        val battery: BatteryTelemetry?,
        val sensors: SensorTelemetry?,
        val safety: SafetyTelemetry?,
        val uptime: Long
    )

    data class CommandStatus(
        val status: String,
        val commandId: String,
        val errorMsg: String
    )

    sealed class Esp32Message {
        data class TelemetryData(val telemetry: Telemetry) : Esp32Message()
        data class CommandConfirmation(val status: CommandStatus) : Esp32Message()
        data class Unknown(val rawJson: String) : Esp32Message()
    }

    fun parse(json: String): Esp32Message {
        return try {
            val obj = JSONObject(json)
            when {
                obj.has("type") && obj.getString("type") == "telemetry" -> {
                    parseTelemetry(obj)
                }
                obj.has("status") -> {
                    parseCommandStatus(obj)
                }
                else -> {
                    Log.w(TAG, "Unknown ESP32 message: $json")
                    Esp32Message.Unknown(json)
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse ESP32 message: $json | Error: ${e.message}")
            Esp32Message.Unknown(json)
        }
    }

    private fun parseTelemetry(obj: JSONObject): Esp32Message {
        val battery = obj.optJSONObject("battery")?.let {
            BatteryTelemetry(
                percentage = it.optInt("percentage", -1),
                busVoltage = it.optDouble("bus_voltage", 0.0),
                currentMa = it.optDouble("current_ma", 0.0),
                sensorOk = it.optBoolean("sensor_ok", false)
            )
        }

        val sensors = obj.optJSONObject("sensors")?.let {
            SensorTelemetry(
                distanceFront = it.optInt("distance_front", -1),
                distanceRear = it.optInt("distance_rear", -1),
                cliffFrontLeft = it.optInt("cliff_front_left", -1),
                cliffFrontRight = it.optInt("cliff_front_right", -1),
                cliffRear = it.optInt("cliff_rear", -1)
            )
        }

        val safety = obj.optJSONObject("safety")?.let {
            SafetyTelemetry(
                cliffActive = it.optBoolean("cliff_active", false),
                obstacleBlocked = it.optBoolean("obstacle_blocked", false)
            )
        }

        val telemetry = Telemetry(
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            battery = battery,
            sensors = sensors,
            safety = safety,
            uptime = obj.optLong("uptime", 0)
        )

        return Esp32Message.TelemetryData(telemetry)
    }

    private fun parseCommandStatus(obj: JSONObject): Esp32Message {
        val status = CommandStatus(
            status = obj.optString("status", "unknown"),
            commandId = obj.optString("command_id", ""),
            errorMsg = obj.optString("error_msg", "")
        )
        return Esp32Message.CommandConfirmation(status)
    }
}

