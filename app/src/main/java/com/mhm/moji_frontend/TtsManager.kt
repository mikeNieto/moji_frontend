package com.mhm.moji_frontend

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.mhm.moji_frontend.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class TtsManager(
    context: Context,
    private val preferences: AppPreferences
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        tts = TextToSpeech(context, this)
    }

    private fun getLocaleFromCode(code: String): Locale {
        return if (code.contains("_")) {
            val parts = code.split("_")
            Locale(parts[0], parts[1])
        } else {
            Locale(code)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageCode = preferences.ttsLanguage
            val locale = getLocaleFromCode(languageCode)
            val result = tts?.setLanguage(locale)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsManager", "Language not supported: $languageCode")
            } else {
                tts?.setSpeechRate(preferences.ttsSpeechRate)
                tts?.setPitch(preferences.ttsPitch)
                isInitialized = true
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("TtsManager", "TTS Started: $utteranceId")
                        if (StateManager.currentState.value != RobotState.LISTENING) {
                            StateManager.updateState(RobotState.RESPONDING)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d("TtsManager", "TTS Done: $utteranceId")
                        abandonAudioFocus()
                        if (StateManager.currentState.value == RobotState.RESPONDING) {
                            StateManager.updateState(RobotState.IDLE)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("TtsManager", "TTS Error: $utteranceId")
                        abandonAudioFocus()
                        if (StateManager.currentState.value == RobotState.RESPONDING) {
                            StateManager.updateState(RobotState.ERROR)
                        }
                    }
                })

                // Hablar en voz alta directamente al iniciar para forzar al motor a cargar correctamente
                speak("Moyi iniciado!")
            }
        } else {
            Log.e("TtsManager", "Initialization Failed!")
        }
    }

    fun speak(text: String): Job {
        return scope.launch(Dispatchers.IO) {
            if (!isInitialized) {
                Log.w("TtsManager", "TTS not initialized yet")
                return@launch
            }
            
            requestAudioFocus()
            
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }
    
    fun stop() {
        if (isInitialized) {
            tts?.stop()
            abandonAudioFocus()
        }
    }

    fun speakChunked(flow: Flow<String>): Job {
        return scope.launch(Dispatchers.IO) {
            if (!isInitialized) return@launch
            
            var buffer = ""
            flow.collect { chunk ->
                buffer += chunk
                if (buffer.contains(Regex("[.!?\\n]"))) {
                    val sentences = buffer.split(Regex("(?<=[.!?\\n])"))
                    for (i in 0 until sentences.size - 1) {
                        val sentence = sentences[i].trim()
                        if (sentence.isNotEmpty()) {
                            requestAudioFocus()
                            tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                        }
                    }
                    buffer = sentences.last()
                }
            }
            if (buffer.trim().isNotEmpty()) {
                requestAudioFocus()
                tts?.speak(buffer.trim(), TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            }
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { }
        }
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        abandonAudioFocus()
    }
}