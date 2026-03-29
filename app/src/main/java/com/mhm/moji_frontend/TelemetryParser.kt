package com.mhm.moji_frontend

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * TelemetryParser: Parses JSON messages received from the ESP32 via BLE RX notifications.
 *
 * Handles:
 *   - "telemetry": periodic robot state, battery and safety data
 *   - optional command confirmation payloads from legacy firmware/debug builds
 */
object TelemetryParser {

    private const val TAG = "TelemetryParser"

    data class BatteryTelemetry(
        val percentage: Int,
        val busVoltage: Double,
        val loadVoltage: Double,
        val shuntVoltageMv: Double,
        val currentMa: Double,
        val powerMw: Double,
        val sensorOk: Boolean
    )

    data class SensorTelemetry(
        val distanceFront: Int,
        val distanceRear: Int,
        val cliffFrontLeft: Int,
        val cliffFrontRight: Int,
        val cliffRear: Int
    )

    data class MotorTelemetry(
        val state: String,
        val lastAction: String
    )

    data class LedTelemetry(
        val mode: String
    )

    data class HeartbeatTelemetry(
        val brainOnline: Boolean
    )

    data class SafetyTelemetry(
        val cliffActive: Boolean,
        val obstacleBlocked: Boolean
    )

    data class Telemetry(
        val timestamp: Long,
        val battery: BatteryTelemetry?,
        val sensors: SensorTelemetry?,
        val motors: MotorTelemetry?,
        val leds: LedTelemetry?,
        val heartbeat: HeartbeatTelemetry?,
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
            val obj = JsonParser.parseString(json).asJsonObject
            when {
                obj.has("type") && obj.get("type")?.asString == "telemetry" -> {
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
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse ESP32 message: $json | Error: ${e.message}")
            Esp32Message.Unknown(json)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Invalid ESP32 JSON shape: $json | Error: ${e.message}")
            Esp32Message.Unknown(json)
        }
    }

    private fun parseTelemetry(obj: JsonObject): Esp32Message {
        val batteryObj = obj.optionalObject("battery")
        val sensorsObj = obj.optionalObject("sensors")
        val motorsObj = obj.optionalObject("motors")
        val ledsObj = obj.optionalObject("leds")
        val heartbeatObj = obj.optionalObject("heartbeat")
        val safetyObj = obj.optionalObject("safety")

        val battery = batteryObj?.let {
            BatteryTelemetry(
                percentage = it.optionalInt("percentage", -1),
                busVoltage = it.optionalDouble("bus_voltage", 0.0),
                loadVoltage = it.optionalDouble("load_voltage", 0.0),
                shuntVoltageMv = it.optionalDouble("shunt_voltage_mv", 0.0),
                currentMa = it.optionalDouble("current_ma", 0.0),
                powerMw = it.optionalDouble("power_mw", 0.0),
                sensorOk = it.optionalBoolean("sensor_ok", false)
            )
        }

        val sensors = sensorsObj?.let {
            SensorTelemetry(
                distanceFront = it.optionalInt("distance_front", -1),
                distanceRear = it.optionalInt("distance_rear", -1),
                cliffFrontLeft = it.optionalInt("cliff_front_left", -1),
                cliffFrontRight = it.optionalInt("cliff_front_right", -1),
                cliffRear = it.optionalInt("cliff_rear", -1)
            )
        }

        val motors = motorsObj?.let {
            MotorTelemetry(
                state = it.optionalString("state", "unknown"),
                lastAction = it.optionalString("last_action", "")
            )
        }

        val leds = ledsObj?.let {
            LedTelemetry(
                mode = it.optionalString("mode", "unknown")
            )
        }

        val heartbeat = heartbeatObj?.let {
            HeartbeatTelemetry(
                brainOnline = it.optionalBoolean("brain_online", false)
            )
        }

        val safety = safetyObj?.let {
            SafetyTelemetry(
                cliffActive = it.optionalBoolean("cliff_active", false),
                obstacleBlocked = it.optionalBoolean("obstacle_blocked", false)
            )
        }

        val telemetry = Telemetry(
            timestamp = obj.optionalLong("timestamp", System.currentTimeMillis()),
            battery = battery,
            sensors = sensors,
            motors = motors,
            leds = leds,
            heartbeat = heartbeat,
            safety = safety,
            uptime = obj.optionalLong("uptime", 0)
        )

        return Esp32Message.TelemetryData(telemetry)
    }

    private fun parseCommandStatus(obj: JsonObject): Esp32Message {
        val status = CommandStatus(
            status = obj.optionalString("status", "unknown"),
            commandId = obj.optionalString("command_id", ""),
            errorMsg = obj.optionalString("error_msg", "")
        )
        return Esp32Message.CommandConfirmation(status)
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? {
        if (!has(key) || get(key).isJsonNull || !get(key).isJsonObject) return null
        return getAsJsonObject(key)
    }

    private fun JsonObject.optionalString(key: String, defaultValue: String): String {
        if (!has(key) || get(key).isJsonNull) return defaultValue
        return get(key).asString
    }

    private fun JsonObject.optionalInt(key: String, defaultValue: Int): Int {
        if (!has(key) || get(key).isJsonNull) return defaultValue
        return get(key).asInt
    }

    private fun JsonObject.optionalLong(key: String, defaultValue: Long): Long {
        if (!has(key) || get(key).isJsonNull) return defaultValue
        return get(key).asLong
    }

    private fun JsonObject.optionalDouble(key: String, defaultValue: Double): Double {
        if (!has(key) || get(key).isJsonNull) return defaultValue
        return get(key).asDouble
    }

    private fun JsonObject.optionalBoolean(key: String, defaultValue: Boolean): Boolean {
        if (!has(key) || get(key).isJsonNull) return defaultValue
        return get(key).asBoolean
    }
}
