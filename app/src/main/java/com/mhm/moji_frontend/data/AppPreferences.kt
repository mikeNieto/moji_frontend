package com.mhm.moji_frontend.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mhm.moji_frontend.BuildConfig
import java.util.UUID

class AppPreferences(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "moji_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String?
        get() = sharedPreferences.getString("api_key", BuildConfig.API_KEY.ifBlank { null })
        set(value) = sharedPreferences.edit().putString("api_key", value).apply()

    var backendUrl: String
        get() = sharedPreferences.getString("backend_url", "wss://192.168.2.200:9393") ?: "wss://192.168.2.200:9393"
        set(value) = sharedPreferences.edit().putString("backend_url", value).apply()

    var serverCertFingerprint: String?
        // Default loaded from BuildConfig (injected from local.properties at build time — never in git)
        get() = sharedPreferences.getString("server_cert_fingerprint", BuildConfig.SERVER_CERT_FINGERPRINT)
        set(value) = sharedPreferences.edit().putString("server_cert_fingerprint", value).apply()

    var deviceId: String
        get() {
            var id = sharedPreferences.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sharedPreferences.edit().putString("device_id", id).apply()
            }
            return id
        }
        set(value) = sharedPreferences.edit().putString("device_id", value).apply()

    var wakeWordSensitivity: Float
        get() = sharedPreferences.getFloat("wake_word_sensitivity", 0.7f)
        set(value) = sharedPreferences.edit().putFloat("wake_word_sensitivity", value).apply()

    var faceRecognitionThreshold: Float
        get() = sharedPreferences.getFloat("face_recognition_threshold", 0.70f)
        set(value) = sharedPreferences.edit().putFloat("face_recognition_threshold", value).apply()

    var faceSearchTimeoutMs: Int
        get() = sharedPreferences.getInt("face_search_timeout_ms", 8000)
        set(value) = sharedPreferences.edit().putInt("face_search_timeout_ms", value).apply()

    var bluetoothDeviceMac: String?
        get() = sharedPreferences.getString("bluetooth_device_mac", null)
        set(value) = sharedPreferences.edit().putString("bluetooth_device_mac", value).apply()

    var ttsLanguage: String
        get() = sharedPreferences.getString("tts_language", "es_US") ?: "es_US"
        set(value) = sharedPreferences.edit().putString("tts_language", value).apply()

    var ttsSpeechRate: Float
        get() = sharedPreferences.getFloat("tts_speech_rate", 1.43f)
        set(value) = sharedPreferences.edit().putFloat("tts_speech_rate", value).apply()

    var ttsPitch: Float
        get() = sharedPreferences.getFloat("tts_pitch", 0.45f)
        set(value) = sharedPreferences.edit().putFloat("tts_pitch", value).apply()

    var lastSync: Long
        get() = sharedPreferences.getLong("last_sync", 0L)
        set(value) = sharedPreferences.edit().putLong("last_sync", value).apply()
}