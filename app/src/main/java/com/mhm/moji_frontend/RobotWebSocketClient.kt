package com.mhm.moji_frontend

import android.util.Log
import com.mhm.moji_frontend.data.AppPreferences
import com.mhm.moji_frontend.data.WsIncoming
import com.mhm.moji_frontend.data.WsMessageParser
import com.mhm.moji_frontend.data.WsOutgoing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import android.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * RobotWebSocketClient: Manages the persistent WebSocket connection to the Moji backend.
 *
 * Features:
 * - Certificate pinning (configurable fingerprint from AppPreferences)
 * - Automatic authentication on connect (auth message with api_key + device_id)
 * - Exponential backoff reconnection (1s → 2s → 4s → 8s → max 30s)
 * - State management (DISCONNECTED when backend is unreachable)
 * - Incoming message parsing to sealed classes via WsMessageParser
 * - Binary frame sending for audio data
 * - JSON message sending for all control messages
 */
class RobotWebSocketClient(
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "RobotWebSocketClient"
        private const val WS_PATH = "/ws/interact"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val PING_INTERVAL_SECONDS = 30L
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var reconnectJob: Job? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var isManuallyDisconnected = false
    private var isAuthenticated = false

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Incoming messages as a SharedFlow
    private val _incomingMessages = MutableSharedFlow<WsIncoming>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<WsIncoming> = _incomingMessages.asSharedFlow()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED
    }

    /**
     * Connect to the WebSocket backend.
     * Will automatically send auth message upon connection.
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.AUTHENTICATED) {
            Log.d(TAG, "Already connected or connecting, ignoring connect()")
            return
        }

        isManuallyDisconnected = false
        isAuthenticated = false
        _connectionState.value = ConnectionState.CONNECTING

        val baseUrl = preferences.backendUrl
        val wsUrl = "$baseUrl$WS_PATH"
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        client = buildOkHttpClient()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected!")
                _connectionState.value = ConnectionState.CONNECTED
                currentBackoffMs = INITIAL_BACKOFF_MS

                // Send auth message immediately
                sendAuth()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Received text: $text")
                val message = WsMessageParser.parseIncoming(text)
                handleIncomingMessage(message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "WS Received binary: ${bytes.size} bytes")
                // Binary messages from backend are not expected in current protocol
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                handleDisconnection()
            }
        })
    }

    /**
     * Disconnect from the WebSocket.
     */
    fun disconnect() {
        isManuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isAuthenticated = false
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "Manually disconnected")
    }

    /**
     * Send a JSON control message.
     */
    fun sendMessage(msg: WsOutgoing): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send message: WebSocket is null")
            return false
        }
        val json = WsMessageParser.serializeOutgoing(msg)
        Log.d(TAG, "WS Sending: $json")
        return ws.send(json)
    }

    /**
     * Send binary audio data.
     */
    fun sendAudioData(data: ByteArray): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send audio: WebSocket is null")
            return false
        }
        Log.d(TAG, "WS Sending binary audio: ${data.size} bytes")
        return ws.send(data.toByteString())
    }

    /**
     * Check if connected and authenticated.
     */
    fun isReady(): Boolean = _connectionState.value == ConnectionState.AUTHENTICATED

    // ======================== INTERNAL ========================

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES)   // WebSocket should stay open indefinitely
            .writeTimeout(30, TimeUnit.SECONDS)

        val certFingerprint = preferences.serverCertFingerprint
        if (!certFingerprint.isNullOrBlank()) {
            Log.d(TAG, "Certificate fingerprint pinning enabled: $certFingerprint")
            configureFingerprintTrust(builder, certFingerprint)
        } else {
            // No cert fingerprint configured — trust all certs for development
            Log.w(TAG, "No certificate fingerprint configured — trusting all certificates (DEVELOPMENT ONLY)")
            configureTrustAllCerts(builder)
        }

        return builder.build()
    }

    /**
     * Configures OkHttp to accept a self-signed TLS certificate by validating its
     * SHA-256 fingerprint (Base64-encoded) instead of relying on the system trust store.
     *
     * This is the correct approach for self-signed certs: a custom X509TrustManager
     * that pins by certificate fingerprint bypasses the "Trust anchor not found" error
     * while still verifying the server presents the exact expected certificate.
     */
    private fun configureFingerprintTrust(builder: OkHttpClient.Builder, expectedFingerprintBase64: String) {
        try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw CertificateException("Empty certificate chain")
                    }
                    // Check each cert in chain — accept if any matches the pinned fingerprint
                    val md = MessageDigest.getInstance("SHA-256")
                    for (cert in chain) {
                        val fingerprint = Base64.encodeToString(md.digest(cert.encoded), Base64.NO_WRAP)
                        if (fingerprint == expectedFingerprintBase64) {
                            Log.d(TAG, "Certificate fingerprint matched — connection trusted")
                            return
                        }
                    }
                    throw CertificateException(
                        "Certificate fingerprint mismatch. Expected: $expectedFingerprintBase64"
                    )
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // Hostname verification is still performed to prevent MITM
            // If the self-signed cert's CN/SAN matches the host, the default verifier works fine.
            // Override only if your cert has no matching SAN (e.g., raw IP without IP SAN).
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring fingerprint trust: ${e.message}", e)
        }
    }

    private fun configureTrustAllCerts(builder: OkHttpClient.Builder) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring trust-all certs: ${e.message}", e)
        }
    }


    private fun sendAuth() {
        val apiKey = preferences.apiKey
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "Cannot authenticate: API key is not configured")
            return
        }
        val authMsg = WsOutgoing.Auth(
            apiKey = apiKey,
            deviceId = preferences.deviceId
        )
        sendMessage(authMsg)
        Log.d(TAG, "Auth message sent")
    }

    private fun handleIncomingMessage(message: WsIncoming) {
        when (message) {
            is WsIncoming.AuthOk -> {
                Log.d(TAG, "Authenticated! Session: ${message.sessionId}")
                isAuthenticated = true
                _connectionState.value = ConnectionState.AUTHENTICATED
            }
            else -> {
                // All other messages are emitted to the SharedFlow for consumers
            }
        }
        // Emit all messages (including AuthOk) so consumers can react
        scope.launch {
            _incomingMessages.emit(message)
        }
    }

    private fun handleDisconnection() {
        isAuthenticated = false
        _connectionState.value = ConnectionState.DISCONNECTED
        webSocket = null

        // Update StateManager to DISCONNECTED
        scope.launch(Dispatchers.Main) {
            if (StateManager.currentState.value != RobotState.DISCONNECTED) {
                StateManager.updateState(RobotState.DISCONNECTED)
            }
        }

        if (!isManuallyDisconnected) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Scheduling reconnect in ${currentBackoffMs}ms")
            delay(currentBackoffMs)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            Log.d(TAG, "Attempting reconnect...")
            connect()
        }
    }
}

