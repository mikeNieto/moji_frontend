package com.mhm.moji_frontend

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
// import androidx.compose.material3.Text             // DESHABILITADO: subtitle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
// import androidx.compose.ui.text.font.FontFamily  // DESHABILITADO: subtitle
// import androidx.compose.ui.text.font.FontWeight  // DESHABILITADO: subtitle
// import androidx.compose.ui.unit.dp               // DESHABILITADO: subtitle
// import androidx.compose.ui.unit.sp               // DESHABILITADO: subtitle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.mhm.moji_frontend.data.AppPreferences
import com.mhm.moji_frontend.ui.theme.Moji_frontendTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@androidx.camera.core.ExperimentalGetImage
class MainActivity : ComponentActivity() {
    private lateinit var ttsManager: TtsManager
    private lateinit var appPreferences: AppPreferences
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var cameraManager: CameraManager
    private lateinit var faceDetectorManager: FaceDetectorManager
    private lateinit var bleManager: BleManager
    private lateinit var esp32Protocol: ESP32Protocol
    private lateinit var heartbeatSender: HeartbeatSender
    private lateinit var faceSearchOrchestrator: FaceSearchOrchestrator
    private lateinit var wsClient: RobotWebSocketClient
    private lateinit var interactionOrchestrator: InteractionOrchestrator
    private var stateObserverJob: Job? = null
    private var hasAttemptedBleConnection = false

    private val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All permissions granted")
            wakeWordDetector.start()
            hasAttemptedBleConnection = true
            bleManager.connect()
            if (bleManager.bleState.value == BleManager.BleState.DISCONNECTED) {
                StateManager.markBleIssue(true)
            }
        } else {
            val denied = permissions.filter { !it.value }.keys
            Toast.makeText(
                this,
                "Se requieren permisos de micrófono, cámara y Bluetooth: $denied",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isEmpty()) {
            Log.d("MainActivity", "All permissions already granted")
            wakeWordDetector.start()
            hasAttemptedBleConnection = true
            bleManager.connect()
            if (bleManager.bleState.value == BleManager.BleState.DISCONNECTED) {
                StateManager.markBleIssue(true)
            }
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appPreferences = AppPreferences(this)
        ttsManager = TtsManager(this, appPreferences)

        // Initialize BLE components (Step 8)
        bleManager = BleManager(context = this, preferences = appPreferences)
        esp32Protocol = ESP32Protocol(bleManager)
        heartbeatSender = HeartbeatSender(esp32Protocol)

        // Observe BLE state: start/stop heartbeat and update robot battery from telemetry
        CoroutineScope(Dispatchers.Main).launch {
            bleManager.bleState.collect { state ->
                Log.d("MainActivity", "BLE state: $state")
                when (state) {
                    BleManager.BleState.READY -> {
                        StateManager.markBleIssue(false)
                        Log.d("MainActivity", "BLE READY — starting heartbeat")
                        heartbeatSender.start()
                    }
                    BleManager.BleState.SCANNING,
                    BleManager.BleState.CONNECTING,
                    BleManager.BleState.CONNECTED -> {
                        StateManager.markBleIssue(false)
                    }
                    BleManager.BleState.DISCONNECTED -> {
                        Log.d("MainActivity", "BLE DISCONNECTED — stopping heartbeat")
                        heartbeatSender.stop()
                        if (hasAttemptedBleConnection) {
                            StateManager.markBleIssue(true)
                        }
                    }
                }
            }
        }

        // Parse incoming telemetry from ESP32 and update robot battery in StateManager
        bleManager.onDataReceived = { json ->
            StateManager.updateLatestBleDebugPayload(json)
            when (val msg = TelemetryParser.parse(json)) {
                is TelemetryParser.Esp32Message.TelemetryData -> {
                    val battery = msg.telemetry.battery
                    if (battery != null && battery.sensorOk) {
                        Log.d("MainActivity", "Robot battery: ${battery.percentage}% (${battery.busVoltage}V, ${battery.currentMa}mA)")
                        StateManager.updateRobotBattery(battery.percentage)
                    }
                }
                is TelemetryParser.Esp32Message.CommandConfirmation -> {
                    val status = msg.status
                    if (status.errorMsg.isNotBlank()) {
                        Log.w("MainActivity", "ESP32 command error: ${status.errorMsg}")
                    } else {
                        Log.d("MainActivity", "ESP32 command OK: id=${status.commandId}")
                    }
                }
                is TelemetryParser.Esp32Message.Unknown -> {
                    Log.w("MainActivity", "Unknown ESP32 message: ${msg.rawJson}")
                }
            }
        }

        // Initialize WebSocket client (Step 7)
        wsClient = RobotWebSocketClient(appPreferences)

        // Initialize camera and face detection (Step 5)
        cameraManager = CameraManager(this)
        faceDetectorManager = FaceDetectorManager()

        // Initialize InteractionOrchestrator (Step 7)
        interactionOrchestrator = InteractionOrchestrator(
            context = this,
            wsClient = wsClient,
            ttsManager = ttsManager,
            cameraManager = cameraManager,
            preferences = appPreferences,
            esp32Protocol = esp32Protocol
        )

        // Wire AudioRecorder to send captured audio via WebSocket
        audioRecorder = AudioRecorder(
            onAudioCaptured = { aacData ->
                Log.d("MainActivity", "Audio capturado y comprimido: ${aacData.size} bytes")
                if (wsClient.isReady()) {
                    interactionOrchestrator.sendAudioData(aacData)
                } else {
                    Log.w("MainActivity", "WebSocket not ready — cannot send audio")
                    // Fallback: go back to IDLE if backend is not connected
                    CoroutineScope(Dispatchers.Main).launch {
                        StateManager.updateState(RobotState.IDLE)
                    }
                }
            },
            continuousListeningManager = interactionOrchestrator.continuousListeningManager
        )

        faceSearchOrchestrator = FaceSearchOrchestrator(
            cameraManager = cameraManager,
            faceDetectorManager = faceDetectorManager,
            ttsManager = ttsManager,
            appPreferences = appPreferences,
            esp32Protocol = esp32Protocol
        )

        // Wire interaction_start to be sent as soon as face recognition finishes
        faceSearchOrchestrator.onInteractionReady = { personId, faceRecognized, faceConfidence ->
            Log.d("MainActivity", "onInteractionReady: personId=$personId, recognized=$faceRecognized, confidence=$faceConfidence")
            interactionOrchestrator.startInteraction(
                personId = personId,
                faceRecognized = faceRecognized,
                faceConfidence = faceConfidence
            )
        }

        // Observe state changes to trigger face search on SEARCHING
        stateObserverJob = CoroutineScope(Dispatchers.Main).launch {
            StateManager.currentState.collect { state ->
                when (state) {
                    RobotState.SEARCHING -> {
                        // TODO: Face recognition temporarily disabled — re-enable when ready
                        // Log.d("MainActivity", "State → SEARCHING: Starting face search")
                        // faceSearchOrchestrator.startSearch(this@MainActivity)
                        Log.d("MainActivity", "State → SEARCHING: Face recognition DISABLED — skipping to LISTENING")
                        interactionOrchestrator.startInteraction(
                            personId = "unknown",
                            faceRecognized = false,
                            faceConfidence = 0f
                        )
                        StateManager.updateState(RobotState.LISTENING)
                    }
                    RobotState.IDLE -> {
                        // Ensure face search is stopped when returning to IDLE
                        if (faceSearchOrchestrator.isActive()) {
                            faceSearchOrchestrator.stopSearch()
                        }
                        // Stop continuous listening when IDLE is reached
                        interactionOrchestrator.continuousListeningManager.stop()
                    }
                    RobotState.ERROR,
                    RobotState.DISCONNECTED -> {
                        if (faceSearchOrchestrator.isActive()) {
                            faceSearchOrchestrator.stopSearch()
                        }
                        interactionOrchestrator.continuousListeningManager.stop()
                    }
                    else -> { /* other states handled elsewhere */ }
                }
            }
        }

        wakeWordDetector = WakeWordDetector(
            context = this,
            ttsManager = ttsManager,
            sensitivity = appPreferences.wakeWordSensitivity,
            onWakeWordDetected = {
                Log.d("MainActivity", "Wake word callback executed")
                // Stop any active face search from a previous interaction
                faceSearchOrchestrator.stopSearch()
                // Stop continuous listening — new interaction cycle
                interactionOrchestrator.continuousListeningManager.stop()
            },
            continuousListeningManager = interactionOrchestrator.continuousListeningManager
        )

        // Start the InteractionOrchestrator (listens to WebSocket messages)
        interactionOrchestrator.start()

        // Connect to WebSocket backend
        wsClient.connect()

        // Request permissions before starting Porcupine
        requestAllPermissions()

        // Activa el modo inmersivo a pantalla completa
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        enableEdgeToEdge()

        setContent {
            Moji_frontendTheme {
                RobotFaceScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            hasAttemptedBleConnection = true
            bleManager.connect()
            if (bleManager.bleState.value == BleManager.BleState.DISCONNECTED) {
                StateManager.markBleIssue(true)
            }
        }
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        interactionOrchestrator.stop()
        wsClient.disconnect()
        faceSearchOrchestrator.release()
        cameraManager.stop()
        audioRecorder.stop()
        wakeWordDetector.stop()
        ttsManager.shutdown()
        heartbeatSender.stop()
        bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
fun RobotFaceScreen() {
    val currentState by StateManager.currentState.collectAsState()
    val currentEmotionTag by StateManager.currentEmotionTag.collectAsState()
    val connectionIssue by StateManager.connectionIssue.collectAsState()
    val latestBleDebugSnapshot by StateManager.latestBleDebugSnapshot.collectAsState()
    var showTelemetryDebug by rememberSaveable { mutableStateOf(false) }
    // val currentSubtitle by StateManager.currentSubtitle.collectAsState() // DESHABILITADO TEMPORALMENTE
    // val isBackendConnected by StateManager.isBackendConnected.collectAsState()

    val expression = when (connectionIssue) {
        ConnectionIssue.BACKEND -> "backend_disconnected"
        ConnectionIssue.BLE -> "ble_disconnected"
        ConnectionIssue.NONE -> currentEmotionTag ?: currentState.name.lowercase()
    }

    // Animación de escala (Bounce) si está respondiendo (hablando)
    val infiniteTransition = rememberInfiniteTransition(label = "face_animation")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (currentState == RobotState.RESPONDING) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_bounce"
    )

    // Animación de escala (Pulse) si está escuchando
    val listeningScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (currentState == RobotState.LISTENING) 1.1f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_pulse"
    )

    // Animación de rotación lenta si está buscando (SEARCHING) o pensando (THINKING)
    val searchingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (currentState == RobotState.SEARCHING || currentState == RobotState.THINKING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "searching_rotation"
    )

    // Animación de rotación rápida para LOADING (cargando)
    val loadingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (currentState == RobotState.LOADING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loading_rotation"
    )

    // Parpadeo lento para DISCONNECTED
    val disconnectedAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (connectionIssue != ConnectionIssue.NONE || currentState == RobotState.DISCONNECTED) 0.3f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "disconnected_blink"
    )

    // Shake horizontal para ERROR
    val errorShake by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (currentState == RobotState.ERROR) 15f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "error_shake"
    )

    val finalScale = when (currentState) {
        RobotState.LISTENING -> listeningScale
        else -> scale
    }
    val finalRotation = when (currentState) {
        RobotState.LOADING -> loadingRotation
        RobotState.SEARCHING, RobotState.THINKING -> searchingRotation
        else -> 0f
    }
    val finalAlpha = if (connectionIssue != ConnectionIssue.NONE || currentState == RobotState.DISCONNECTED) disconnectedAlpha else 1f
    val finalTranslationX = if (currentState == RobotState.ERROR) errorShake else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Fondo negro absoluto según arquitectura
    ) {
        val emojiUrl = ExpressionManager.getEmojiUrl(expression)
        val context = LocalContext.current
        
        // Capa 1: Círculo azul claro decorativo (50% del alto), independiente del emoji
        Box(
            modifier = Modifier
                .fillMaxHeight(0.7f)
                .aspectRatio(1f)
                .align(Alignment.Center)
                .background(Color(0xFF1B86AB), shape = CircleShape)
        )

        // Capa 2: Emoji flotando encima (80% del alto), independiente del círculo
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(emojiUrl)
                .decoderFactory(SvgDecoder.Factory())
                .crossfade(true)
                .build(),
            contentDescription = "Cara de Moji ($expression)",
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .aspectRatio(1f)
                .align(Alignment.Center)
                .graphicsLayer(
                    scaleX = finalScale,
                    scaleY = finalScale,
                    rotationZ = finalRotation,
                    alpha = finalAlpha,
                    translationX = finalTranslationX
                ),
            contentScale = ContentScale.Fit
        )

        if (showTelemetryDebug) {
            TelemetryDebugPanel(
                snapshot = latestBleDebugSnapshot,
                onClose = { showTelemetryDebug = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 56.dp)
                    .fillMaxWidth(0.92f)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 10.dp)
                .size(48.dp)
                .clickable { showTelemetryDebug = !showTelemetryDebug }
        )

        // Texto Inferior (10% de la pantalla) — DESHABILITADO TEMPORALMENTE
        // Text(
        //     text = currentSubtitle,
        //     color = Color(0xFF88CCEE), // Azul claro metalizado
        //     fontSize = 24.sp,
        //     fontFamily = FontFamily.Monospace,
        //     fontWeight = FontWeight.Medium,
        //     modifier = Modifier
        //         .align(Alignment.BottomCenter)
        //         .padding(bottom = 32.dp)
        // )

    }
}

@Composable
private fun TelemetryDebugPanel(
    snapshot: TelemetryDebugSnapshot,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val updatedAtLabel = remember(snapshot.updatedAtMillis) {
        if (snapshot.updatedAtMillis == 0L) {
            "sin datos"
        } else {
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(snapshot.updatedAtMillis))
        }
    }

    Card(
        modifier = modifier
            .widthIn(max = 520.dp)
            .heightIn(max = 360.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF101820).copy(alpha = 0.95f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug BLE",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Cerrar",
                    color = Color(0xFF88CCEE),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(onClick = onClose)
                )
            }
            Text(
                text = "Payload: ${snapshot.payloadType} • $updatedAtLabel",
                color = Color(0xFF88CCEE),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White.copy(alpha = 0.14f)
            )

            when {
                snapshot.rawJson.isBlank() -> {
                    Text(
                        text = "Aún no ha llegado ningún payload BLE.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                snapshot.parseError != null -> {
                    Text(
                        text = "JSON inválido: ${snapshot.parseError}",
                        color = Color(0xFFFFA4A4),
                        fontFamily = FontFamily.Monospace
                    )
                }
                snapshot.rows.isEmpty() -> {
                    Text(
                        text = "El payload BLE no contiene campos para mostrar.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "Campo",
                            modifier = Modifier.weight(1.2f),
                            color = Color(0xFF88CCEE),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Valor",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF88CCEE),
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        itemsIndexed(
                            items = snapshot.rows,
                            key = { index, row -> "${row.path}-$index" }
                        ) { index, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (index % 2 == 0) Color.White.copy(alpha = 0.04f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = row.path,
                                    modifier = Modifier.weight(1.2f),
                                    color = Color(0xFFCEEFFF),
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Text(
                                    text = row.value,
                                    modifier = Modifier.weight(1f),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
