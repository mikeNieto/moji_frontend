package com.mhm.moji_frontend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RobotState(val emojiCode: String, val description: String) {
    LOADING("231B", "Cargando..."),
    IDLE("1F916", "Moji está reposando"),
    LISTENING("1F442", "Escuchando..."),
    SEARCHING("1F50D", "Buscando..."),
    GREETING("1F44B", "¡Hola!"),
    REGISTERING("2753", "¿Cómo te llamas?"),
    THINKING("1F914", "Pensando..."),
    RESPONDING("", "Hablando..."), // Depends on emotion tag
    ERROR("1F615", "Ups, ocurrió un error."),
    DISCONNECTED("1F50C", "Desconectado")
}

object StateManager {
    private val _currentState = MutableStateFlow(RobotState.LOADING)
    val currentState: StateFlow<RobotState> = _currentState.asStateFlow()

    private val _currentEmotionTag = MutableStateFlow<String?>(null)
    val currentEmotionTag: StateFlow<String?> = _currentEmotionTag.asStateFlow()
    
    // Subtítulos
    private val _currentSubtitle = MutableStateFlow(RobotState.LOADING.description)
    val currentSubtitle: StateFlow<String> = _currentSubtitle.asStateFlow()

    // Robot battery level (from ESP32 telemetry via BLE)
    private val _robotBatteryLevel = MutableStateFlow(100)
    val robotBatteryLevel: StateFlow<Int> = _robotBatteryLevel.asStateFlow()

    // WebSocket connection status
    private val _isBackendConnected = MutableStateFlow(false)
    val isBackendConnected: StateFlow<Boolean> = _isBackendConnected.asStateFlow()

    fun updateState(newState: RobotState) {
        _currentState.value = newState
        _currentSubtitle.value = newState.description
        if (newState != RobotState.RESPONDING) {
            _currentEmotionTag.value = null
        }
    }

    fun updateEmotion(emotionTag: String) {
        _currentEmotionTag.value = emotionTag
    }
    
    fun updateSubtitle(subtitle: String) {
        _currentSubtitle.value = subtitle
    }

    fun updateRobotBattery(level: Int) {
        _robotBatteryLevel.value = level
    }

    fun updateBackendConnection(connected: Boolean) {
        _isBackendConnected.value = connected
    }
}