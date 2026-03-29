package com.mhm.moji_frontend

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal

data class TelemetryDebugRow(
    val path: String,
    val value: String
)

data class TelemetryDebugSnapshot(
    val rawJson: String,
    val payloadType: String,
    val updatedAtMillis: Long,
    val rows: List<TelemetryDebugRow>,
    val parseError: String? = null
) {
    companion object {
        val EMPTY = TelemetryDebugSnapshot(
            rawJson = "",
            payloadType = "sin_datos",
            updatedAtMillis = 0L,
            rows = emptyList(),
            parseError = null
        )
    }
}

object TelemetryDebugFormatter {

    fun fromJson(json: String, receivedAtMillis: Long = System.currentTimeMillis()): TelemetryDebugSnapshot {
        return try {
            val root = JsonParser.parseString(json)
            val rows = mutableListOf<TelemetryDebugRow>()
            flatten(root, currentPath = "", rows = rows)

            TelemetryDebugSnapshot(
                rawJson = json,
                payloadType = root.asPayloadType(),
                updatedAtMillis = receivedAtMillis,
                rows = rows,
                parseError = null
            )
        } catch (error: Exception) {
            TelemetryDebugSnapshot(
                rawJson = json,
                payloadType = "json_invalido",
                updatedAtMillis = receivedAtMillis,
                rows = emptyList(),
                parseError = error.message ?: "No se pudo interpretar el JSON BLE"
            )
        }
    }

    private fun flatten(element: JsonElement, currentPath: String, rows: MutableList<TelemetryDebugRow>) {
        when {
            element.isJsonNull -> rows += TelemetryDebugRow(currentPath.asDisplayPath(), "null")
            element.isJsonPrimitive -> rows += TelemetryDebugRow(currentPath.asDisplayPath(), element.formatPrimitive())
            element.isJsonObject -> flattenObject(element.asJsonObject, currentPath, rows)
            element.isJsonArray -> {
                if (element.asJsonArray.size() == 0) {
                    rows += TelemetryDebugRow(currentPath.asDisplayPath(), "[]")
                } else {
                    element.asJsonArray.forEachIndexed { index, child ->
                        val nextPath = if (currentPath.isBlank()) "[$index]" else "$currentPath[$index]"
                        flatten(child, nextPath, rows)
                    }
                }
            }
        }
    }

    private fun flattenObject(obj: JsonObject, currentPath: String, rows: MutableList<TelemetryDebugRow>) {
        if (obj.entrySet().isEmpty() && currentPath.isNotBlank()) {
            rows += TelemetryDebugRow(currentPath.asDisplayPath(), "{}")
            return
        }

        obj.entrySet().forEach { (key, value) ->
            val nextPath = if (currentPath.isBlank()) key else "$currentPath.$key"
            flatten(value, nextPath, rows)
        }
    }

    private fun JsonElement.asPayloadType(): String {
        if (!isJsonObject) return "payload"
        val typeElement = asJsonObject.get("type")
        return if (typeElement == null || typeElement.isJsonNull) "payload" else typeElement.asString
    }

    private fun JsonElement.formatPrimitive(): String {
        val primitive = asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean.toString()
            primitive.isNumber -> primitive.asString.formatNumber()
            else -> primitive.asString
        }
    }

    private fun String.formatNumber(): String {
        return runCatching { BigDecimal(this).stripTrailingZeros().toPlainString() }
            .getOrElse { this }
    }

    private fun String.asDisplayPath(): String = if (isBlank()) "(root)" else this
}

