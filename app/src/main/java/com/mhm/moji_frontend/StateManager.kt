package com.mhm.moji_frontend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RobotState(val emojiCode: String, val description: String) {
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
    private val _currentState = MutableStateFlow(RobotState.IDLE)
    val currentState: StateFlow<RobotState> = _currentState.asStateFlow()

    private val _currentEmotionTag = MutableStateFlow<String?>(null)
    val currentEmotionTag: StateFlow<String?> = _currentEmotionTag.asStateFlow()
    
    // Subtítulos
    private val _currentSubtitle = MutableStateFlow(RobotState.IDLE.description)
    val currentSubtitle: StateFlow<String> = _currentSubtitle.asStateFlow()

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
}