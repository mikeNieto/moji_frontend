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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.mhm.moji_frontend.data.AppPreferences
import com.mhm.moji_frontend.ui.theme.Moji_frontendTheme
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
    private lateinit var faceSearchOrchestrator: FaceSearchOrchestrator
    private var stateObserverJob: Job? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All permissions granted")
            wakeWordDetector.start()
        } else {
            val denied = permissions.filter { !it.value }.keys
            Toast.makeText(
                this,
                "Se requieren permisos de micrófono y cámara: $denied",
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
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appPreferences = AppPreferences(this)
        ttsManager = TtsManager(this, appPreferences)
        
        audioRecorder = AudioRecorder(
            onAudioCaptured = { aacData ->
                // TODO: Send data via WebSocket
                Log.d("MainActivity", "Audio capturado y comprimido: ${aacData.size} bytes")
            }
        )

        // Initialize camera and face detection (Step 5)
        cameraManager = CameraManager(this)
        faceDetectorManager = FaceDetectorManager()
        faceSearchOrchestrator = FaceSearchOrchestrator(
            cameraManager = cameraManager,
            faceDetectorManager = faceDetectorManager,
            ttsManager = ttsManager,
            appPreferences = appPreferences
        )

        // Observe state changes to trigger face search on SEARCHING
        stateObserverJob = CoroutineScope(Dispatchers.Main).launch {
            StateManager.currentState.collect { state ->
                when (state) {
                    RobotState.SEARCHING -> {
                        Log.d("MainActivity", "State → SEARCHING: Starting face search")
                        faceSearchOrchestrator.startSearch(this@MainActivity)
                    }
                    RobotState.IDLE, RobotState.ERROR -> {
                        // Ensure face search is stopped when returning to IDLE or ERROR
                        if (faceSearchOrchestrator.isActive()) {
                            faceSearchOrchestrator.stopSearch()
                        }
                    }
                    else -> { /* other states handled elsewhere */ }
                }
            }
        }

        wakeWordDetector = WakeWordDetector(
            context = this,
            appPreferences = appPreferences,
            ttsManager = ttsManager,
            onWakeWordDetected = {
                Log.d("MainActivity", "Wake word callback executed")
                // Stop any active face search from a previous interaction
                faceSearchOrchestrator.stopSearch()
            }
        )

        // Request permissions before starting Porcupine
        requestAllPermissions()

        // Activa el modo inmersivo a pantalla completa
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        enableEdgeToEdge()

        setContent {
            Moji_frontendTheme {
                RobotFaceScreen(
                    onTestSpeak = {
                        StateManager.updateEmotion("happy")
                        ttsManager.speak("Hola. Esta es mi voz. Espero te guste!")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        faceSearchOrchestrator.release()
        cameraManager.stop()
        audioRecorder.stop()
        wakeWordDetector.stop()
        ttsManager.shutdown()
        super.onDestroy()
    }
}

@Composable
fun RobotFaceScreen(onTestSpeak: () -> Unit = {}) {
    val currentState by StateManager.currentState.collectAsState()
    val currentEmotionTag by StateManager.currentEmotionTag.collectAsState()
    val currentSubtitle by StateManager.currentSubtitle.collectAsState()

    // Determine the expression string for ExpressionManager based on state
    val expression = currentEmotionTag ?: currentState.name.lowercase()

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

    // Animación de rotación lenta si está buscando (SEARCHING)
    val searchingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (currentState == RobotState.SEARCHING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "searching_rotation"
    )

    val finalScale = if (currentState == RobotState.LISTENING) listeningScale else scale
    val finalRotation = if (currentState == RobotState.SEARCHING) searchingRotation else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Fondo negro absoluto según arquitectura
    ) {
        val emojiUrl = ExpressionManager.getEmojiUrl(expression)
        val context = LocalContext.current
        
        // Emoji Centrado (80% del alto)
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(emojiUrl)
                .decoderFactory(SvgDecoder.Factory())
                .crossfade(true)
                .build(),
            contentDescription = "Cara de Moji ($expression)",
            modifier = Modifier
                .fillMaxHeight(0.8f) // 80% de la pantalla
                .align(Alignment.Center)
                .graphicsLayer(
                    scaleX = finalScale,
                    scaleY = finalScale,
                    rotationZ = finalRotation
                ),
            contentScale = ContentScale.Fit
        )

        // Texto Inferior (10% de la pantalla)
        Text(
            text = currentSubtitle,
            color = Color(0xFF88CCEE), // Azul claro metalizado
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        // Esquina superior izquierda (cambia estados)
        Box(
            modifier = Modifier
                .size(100.dp)
                .align(Alignment.TopStart)
                .background(Color.Transparent)
                .clickable {
                    val nextState = RobotState.entries[(currentState.ordinal + 1) % RobotState.entries.size]
                    StateManager.updateState(nextState)
                }
        )

        // Esquina superior derecha (botón oculto de prueba de TTS)
        Box(
            modifier = Modifier
                .size(100.dp)
                .align(Alignment.TopEnd)
                .background(Color.Transparent)
                .clickable {
                    onTestSpeak()
                }
        )
    }
}
