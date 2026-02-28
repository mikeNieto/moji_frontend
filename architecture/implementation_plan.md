# Documento de Implementación: Moji Android App (v2.0)

**Propósito de este documento:** Guía completa, autosuficiente e incremental para implementar la app Android del robot Moji. Cualquier IA implementadora debe poder leer **solo este documento** para entender qué construir en cada paso sin necesitar el documento de arquitectura general.

---

## Instrucciones para la IA Implementadora

1. **NO implementes todo el documento de una vez.** El desarrollador te pedirá explícitamente qué "Paso" implementar.
2. Cada paso asume que el anterior funciona perfectamente.
3. Escribe código limpio en **Kotlin**, usando **Coroutines/Flows** para la asincronía y arquitectura **MVVM o MVI ligero**.
4. Cada paso tiene un criterio de éxito verificable antes de continuar.
5. Este documento es la única fuente de verdad para el desarrollo Android. No se requiere consultar ningún otro archivo de arquitectura.

---

## Contexto del Proyecto: ¿Qué es Moji?

Moji es un **robot doméstico físico** que vive con una familia. NO es un asistente de tareas: es un **amigo familiar curioso, empático y ético**. El robot tiene un cuerpo físico (ESP32, motores, LEDs) y un "cerebro" (backend Python/FastAPI con Gemini Flash Lite como LLM).

**Arquitectura del sistema:**
- **App Android** (este proyecto): corre en un teléfono Android montado en el cuerpo del robot. Es los ojos, oídos y voz de Moji. Gestiona la cámara frontal, el micrófono, el TTS, el reconocimiento facial on-device, la interfaz visual y la comunicación BLE con el ESP32.
- **Backend FastAPI** (servidor local): corre en Docker en un PC de la red local (`192.168.2.200:9393`). Contiene el LLM (Gemini Flash Lite), la base de datos de personas y memorias, y la lógica conversacional. La app se conecta vía WebSocket.
- **ESP32** (microcontrolador físico): controla los motores, los LEDs y los sensores. La app lo controla vía Bluetooth Low Energy (BLE).

**Personalidad de Moji (importante para entender el flujo):**
- Llama a las personas por su nombre cuando lo conoce.
- Recuerda detalles de conversaciones anteriores.
- Puede iniciar conversación cuando detecta una persona.
- Nunca guarda datos privados (contraseñas, datos médicos, financieros).
- Rechaza amablemente cualquier orden que implique daño o algo ilegal.
- Avisa cuando su batería o la del teléfono está baja.

**Reglas éticas (el robot no las rompe nunca):**
- No se acerca a una persona que no quiere interactuar.
- No entra en habitaciones marcadas como restringidas.
- No comparte información privada entre miembros de la familia.
- No realiza acciones físicas que puedan causar daño.

---

## Stack Tecnológico Android (Resumen Completo)

| Componente | Tecnología | Notas |
|---|---|---|
| Lenguaje | Kotlin | Coroutines + Flows para async |
| Arquitectura | MVVM ligero | ViewModel + StateFlow |
| UI | Immersive Mode, fondo negro, landscape fijo | Sin botones de control |
| Emojis visuales | OpenMoji CDN | SVG, carga lazy + caché LRU 50MB |
| Wake Word | Porcupine ("Hey Moji") | Local, ~<100ms latencia |
| Audio grabación | AudioRecord API | AAC, 16kHz, mono, VAD 2s silencio |
| TTS | Android TextToSpeech del sistema | On-device, sin costo, sin latencia de red |
| Reconocimiento facial | ML Kit Face Detection + TFLite FaceNet | On-device, <200ms, cámara frontal exclusivamente |
| Base de datos local | Room (SQLite) | Embeddings faciales 128D |
| Preferencias seguras | EncryptedSharedPreferences + Android Keystore | API key, certificado, configuración |
| Caché emojis | LRU Cache en disco | /cache/openmoji/ |
| WebSocket | OkHttp WebSocket | TLS con certificate pinning |
| REST auxiliar | Retrofit + OkHttp | Solo 2 endpoints: /api/health y /api/restore |
| Bluetooth | BLE (BluetoothLeScanner + GATT) | UART Nordic Service UUID |
| Cámara | CameraX | Solo cámara frontal (LENS_FACING_FRONT) |
| Versión mínima | Android 7.0 (API 24) | |
| Target SDK | Android 13 (API 33) | |
| Orientación | Landscape fija, inamovible | screenOrientation="landscape" |

---

## Máquina de Estados Completa

La app tiene una única máquina de estados centralizada (`StateManager`). Todos los componentes leen el estado actual vía `StateFlow`. Los estados se mapean directamente a un emoji visible en pantalla.

| Estado | Emoji | Código Unicode | Descripción |
|---|---|---|---|
| `IDLE` | 🤖 | `1F916` | Reposo. Wake word activo. |
| `LISTENING` | 👂 | `1F442` | Wake word detectado. Grabando/escuchando. |
| `SEARCHING` | 🔍 | `1F50D` | Buscando persona con cámara frontal. ESP32 rota ±90°. |
| `GREETING` | 👋 | `1F44B` | Persona reconocida. Enviando saludo al backend. |
| `REGISTERING` | ❓ | `2753` | Persona desconocida. Preguntando nombre. |
| `THINKING` | 🤔 | `1F914` | Audio enviado. Esperando respuesta del backend. |
| `RESPONDING` | _(emotion tag del LLM)_ | _(varios)_ | Reproduciendo respuesta con TTS. |
| `ERROR` | 😕 | `1F615` | Error de red/cámara/timeout. Dura 2s → IDLE. |
| `DISCONNECTED` | 🔌 | `1F50C` | Backend no disponible. Parpadeante. |

### Transition Rules

```
IDLE → LISTENING:            Wake word "Hey Moji" detectado (inmediato)
LISTENING → SEARCHING:       Comienza búsqueda de persona con cámara (inmediato)
SEARCHING → GREETING:        Rostro reconocido (similitud coseno > 0.70)
SEARCHING → REGISTERING:     Rostro detectado pero desconocido (similitud ≤ 0.70)
SEARCHING → LISTENING:       Timeout sin rostro (8s) → TTS "No puedo verte"
GREETING → LISTENING:        Saludo completado → modo escucha continua 60s
REGISTERING → LISTENING:     Registro completado → modo escucha continua 60s
LISTENING → THINKING:        Audio capturado (silencio 2s o timeout 10s)
THINKING → RESPONDING:       Backend emite primer token (emotion tag recibido)
RESPONDING → LISTENING:      stream_end recibido → continúa escucha continua 60s
RESPONDING → IDLE:           stream_end recibido + timeout escucha continua agotado
ANY → ERROR:                 Error de red / timeout / error cámara (2s → IDLE automático)
ANY → DISCONNECTED:          Backend WebSocket desconectado
```

### Escucha Continua (Conversación Fluida)

Tras la primera interacción, Moji entra en **modo de escucha continua de 60 segundos**:
- El usuario puede seguir hablando sin repetir "Hey Moji".
- Cada vez que hay 2s de silencio tras hablar, el audio se graba y envía al backend.
- El contador de 60s se reinicia con cada interacción exitosa.
- Si pasan 60s sin actividad → regresa a `IDLE`.
- El wake word solo vuelve a ser necesario cuando se regresa a `IDLE`.

---

## Protocolo WebSocket Completo (Android ↔ Backend)

### Conexión

```
URL: wss://192.168.2.200:9393/ws/interact
Autenticación: API Key en el primer mensaje JSON ("auth")
Protocolo: JSON (mensajes de control) + Binary (audio del usuario)
Keepalive: Ping/Pong cada 30s (OkHttp lo gestiona automáticamente)
Reconexión: Backoff exponencial (1s → 2s → 4s → 8s → máx 30s)
Certificate Pinning: Habilitado (fingerprint del cert TLS autofirmado del servidor)
```

### Mensajes que envía Android → Backend

```json
// 1. Handshake (primer mensaje tras conectar — SIEMPRE el primero)
{
  "type": "auth",
  "api_key": "<clave configurada en AppPreferences>",
  "device_id": "<UUID fijo del dispositivo Android>"
}

// 2. Inicio de interacción
// person_id: ID de la persona identificada localmente, o "unknown" si no se reconoció
// face_embedding: solo cuando hay persona desconocida (para que backend la registre)
{
  "type": "interaction_start",
  "request_id": "<uuid-v4>",
  "person_id": "person_juan_abc",
  "face_recognized": true,
  "face_confidence": 0.87,
  "face_embedding": null,
  "context": {
    "battery_robot": 75,
    "battery_phone": 82,
    "sensors": {}
  }
}

// 3. Audio (binario puro — frames AAC/Opus 16kHz mono, enviados tras interaction_start)

// 4. Fin de audio (siempre enviar después del último frame binario)
{"type": "audio_end", "request_id": "<uuid-v4>"}

// 5. Imagen (foto de contexto, por solicitud del backend via capture_request)
{
  "type": "image",
  "request_id": "<uuid-v4>",
  "purpose": "context",
  "data": "<base64-jpeg>"
}

// 6. Video (por solicitud del backend via capture_request)
{
  "type": "video",
  "request_id": "<uuid-v4>",
  "duration_ms": 10000,
  "data": "<base64-mp4>"
}

// 7. Texto directo (alternativa a audio — principalmente para tests)
{
  "type": "text",
  "request_id": "<uuid-v4>",
  "content": "¿Qué está en la cocina?",
  "person_id": "person_juan_abc"
}

// 8. Modo de escaneo facial activo (Moji buscando caras proactivamente)
{"type": "face_scan_mode", "request_id": "<uuid-v4>"}

// 9. Persona detectada por cámara (resultado del reconocimiento on-device)
{
  "type": "person_detected",
  "request_id": "<uuid-v4>",
  "known": false,
  "person_id": null,
  "confidence": 0.72,
  "face_embedding": "<base64 del vector 128D serializado>"
}

// 10. Alerta de batería baja
{
  "type": "battery_alert",
  "request_id": "<uuid-v4>",
  "battery_level": 12,
  "source": "phone"
}
```

### Mensajes que recibe Android ← Backend (streaming)

El backend envía los mensajes **siempre en este orden** para cada interacción:

**1. `emotion`** → **2. N × `text_chunk`** → **3. (opcional) `capture_request`** → **4. `response_meta`** → **5. `stream_end`**

```json
// Confirmación de autenticación (respuesta al msg "auth")
{"type": "auth_ok", "session_id": "<uuid-v4>"}

// Persona registrada (confirmación del backend tras registrar nueva persona)
{
  "type": "person_registered",
  "person_id": "person_maria_b7f3c2",
  "name": "María"
}

// [1] Emotion tag — PRIMER mensaje de cada interacción.
// Actualizar la cara del robot INMEDIATAMENTE al recibir esto, antes de que el TTS hable.
{
  "type": "emotion",
  "request_id": "<uuid-v4>",
  "emotion": "curious",
  "person_identified": "person_juan_abc",
  "confidence": 0.87
}

// [2] Fragmento de texto — llegan múltiples en streaming. Acumular y enviar al TTS.
{
  "type": "text_chunk",
  "request_id": "<uuid-v4>",
  "text": "¡Hola! ¿Cómo estás hoy?"
}

// [3] Solicitud de captura (opcional) — cuando el usuario pidió foto/video por voz.
// Android debe capturar y enviar el resultado antes de que el backend pueda responder.
{
  "type": "capture_request",
  "request_id": "<uuid-v4>",
  "capture_type": "photo",
  "duration_ms": null
}

// [4] Metadata de respuesta — incluye emojis contextuales y acciones físicas para ESP32.
// person_name: solo presente cuando el LLM acaba de deducir el nombre de una persona nueva.
{
  "type": "response_meta",
  "request_id": "<uuid-v4>",
  "response_text": "¡Hola Juan!",
  "person_name": null,
  "expression": {
    "emojis": ["1F44B", "1F60A"],
    "duration_per_emoji": 2000,
    "transition": "bounce"
  },
  "actions": [
    {"type": "turn_right_deg", "degrees": 30, "speed": 40, "duration_ms": 600},
    {"type": "move_forward_cm", "cm": 50, "speed": 50, "duration_ms": 1500},
    {"type": "led_color", "r": 0, "g": 200, "b": 100, "duration_ms": 1000},
    {"type": "wave"},
    {
      "type": "move_sequence",
      "total_duration_ms": 2400,
      "emotion_during": "happy",
      "steps": [
        {"type": "turn_right_deg", "degrees": 45, "speed": 40, "duration_ms": 800},
        {"type": "turn_left_deg", "degrees": 45, "speed": 40, "duration_ms": 800},
        {"type": "led_color", "r": 0, "g": 255, "b": 0, "duration_ms": 800}
      ]
    }
  ]
}

// Acciones de escaneo facial (ESP32 debe girar buscando caras)
{
  "type": "face_scan_actions",
  "request_id": "<uuid-v4>",
  "actions": [
    {"type": "turn_right_deg", "degrees": 90, "speed": 25, "duration_ms": 1500},
    {"type": "turn_left_deg", "degrees": 180, "speed": 25, "duration_ms": 3000}
  ]
}

// [5] Fin de stream — la interacción está completamente procesada.
{"type": "stream_end", "request_id": "<uuid-v4>", "processing_time_ms": 820}

// Error — puede ocurrir en cualquier momento.
{
  "type": "error",
  "request_id": "<uuid-v4>",
  "error_code": "GEMINI_TIMEOUT",
  "message": "Error al procesar el audio",
  "recoverable": true
}
```

---

## Sistema de Emociones y Emojis

El LLM del backend incluye **emotion tags** al inicio de cada respuesta. Android debe parsear estos tags y actualizar la cara del robot **antes de que el TTS empiece a hablar**, garantizando sincronía visual-vocal.

### Mapeo Emotion Tag → Emojis OpenMoji

Cuando llega un `emotion` de tipo:
- `happy` → elegir aleatoriamente uno de: `1F600`, `1F603`, `1F604`, `1F60A`
- `excited` → elegir de: `1F929`, `1F389`, `1F38A`, `2728`
- `sad` → elegir de: `1F622`, `1F625`, `1F62D`
- `empathy` → elegir de: `1F97A`, `1F615`, `2764-FE0F`
- `confused` → elegir de: `1F615`, `1F914`, `2753`
- `surprised` → elegir de: `1F632`, `1F62E`, `1F92F`
- `love` → elegir de: `2764-FE0F`, `1F60D`, `1F970`, `1F498`
- `cool` → elegir de: `1F60E`, `1F44D`, `1F525`
- `greeting` → elegir de: `1F44B`, `1F917`
- `neutral` → elegir de: `1F642`, `1F916`
- `curious` → elegir de: `1F9D0`, `1F50D`
- `worried` → elegir de: `1F61F`, `1F628`
- `playful` → elegir de: `1F61C`, `1F609`, `1F638`

Los emojis de estado fijo (IDLE, LISTENING, etc.) no dependen del LLM y son siempre el emoji listado en la tabla de estados.

### Cómo Cargar Emojis (OpenMoji CDN)

```
URL base: https://openmoji.org/data/color/svg/<HEXCODE>.svg
Ejemplo: https://openmoji.org/data/color/svg/1F600.svg

Pre-cargar en caché al iniciar la app (20 emojis de uso frecuente):
  Estados: 1F916, 1F442, 1F914, 1F615, 1F50C, 1F50D, 1F44B, 2753
  Emociones: 1F600, 1F603, 1F622, 1F97A, 1F632, 2764-FE0F, 1F60E, 1F44D

Caché: LRU en disco, máximo 50MB, directorio /cache/openmoji/
El resto se descarga y cachea automáticamente la primera vez que se necesita.
```

---

## Reconocimiento Facial On-Device: Especificaciones Completas

El reconocimiento facial ocurre **completamente en el dispositivo Android**, sin enviar imágenes al backend. Solo se usa la **cámara frontal** (LENS_FACING_FRONT).

### Pipeline

```
[Frames cámara frontal (CameraX ImageAnalysis)]
        ↓  ~10 fps
[ML Kit FaceDetector → bounding box del rostro]
        ↓  si hay rostro
[Crop + resize a 112×112px RGB normalizado [-1,1]]
        ↓
[TFLite FaceNet → embedding float32 128D]
        ↓
[FaceSimilarityEngine → cosine similarity vs. todos los embeddings en Room SQLite]
        ↓
  similitud > 0.70 → persona conocida (person_id + name + score)
  similitud ≤ 0.70 → persona desconocida
```

### Parámetros

```
Modelo TFLite: facenet.tflite (128D, ~20MB, incluido en assets/)
Input: 112×112 RGB normalizado a rango [-1, 1]
Output: vector float32 de 128 dimensiones, L2-normalizado
Métrica: Similitud coseno
Umbral identificación: 0.70 (configurable en AppPreferences)
FPS análisis: ~10 fps (sin saturar CPU)
Latencia total (detección + embedding): <200ms
Timeout búsqueda: 8s sin rostro → "No puedo verte"
```

### Esquema Room Database (SQLite local en Android)

```sql
-- Tabla para almacenar embeddings faciales en el dispositivo
CREATE TABLE face_embeddings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    person_id   TEXT NOT NULL,   -- ID generado por el backend (sincronizado)
    name        TEXT NOT NULL,   -- Nombre de la persona
    embedding   BLOB NOT NULL,   -- Float array 128D serializado como ByteArray
    created_at  INTEGER NOT NULL,
    last_seen   INTEGER
);
CREATE UNIQUE INDEX idx_person_id ON face_embeddings(person_id);
```

### Flujo de Registro de Nueva Persona

```
1. Android detecta rostro desconocido → captura embedding + frame JPEG
2. Envía WS: interaction_start con person_id="unknown" + face_embedding (base64)
3. Backend: Gemini pregunta "¿Cómo te llamas?" → Android TTS reproduce
4. Android graba respuesta del usuario → envía audio al backend
5. Backend: Gemini extrae nombre del audio → genera person_id único
6. Backend envía WS: person_registered {person_id, name}
7. Android: guarda embedding en Room SQLite local con ese person_id y name
8. Backend envía WS: text_chunk "¡Mucho gusto, [nombre]!"
9. Android TTS reproduce la bienvenida
```

---

## Audio: Especificaciones Técnicas

```
API: AudioRecord
Formato: PCM 16-bit → comprimido a AAC antes de enviar
Sample Rate: 16000 Hz (suficiente para voz)
Bitrate: 64 kbps
Canales: Mono
Buffer: 1024 frames
Detección de silencio (VAD): 2 segundos de RMS bajo el umbral → fin de grabación
Timeout máximo: 10 segundos (aunque no haya silencio)
Flujo al backend: Binario puro enviado por WebSocket tras interaction_start
```

---

## TTS: Configuración

```
API: Android TextToSpeech (android.speech.tts)
Motor: El del sistema operativo (Google TTS, Samsung TTS, etc.)
No se requieren librerías adicionales.

Configuración por defecto:
  tts_language: "es"             (idioma español)
  tts_speech_rate: 0.9           (ligeramente más lento que el normal, más claro)
  tts_pitch: 1.0                 (tono neutro)
  tts_audio_focus: true          (solicitar foco de audio al hablar)

Reproducción en streaming:
  - Los text_chunk del WebSocket se acumulan en un buffer de oraciones
  - Al detectar fin de oración (punto, exclamación, interrogación, salto de línea)
    el buffer se envía al TTS para síntesis inmediata
  - Ventaja: el robot empieza a hablar con el primer chunk, sin esperar el texto completo

Callbacks:
  - UtteranceProgressListener.onStart → actualizar UI (cara "hablando")
  - UtteranceProgressListener.onDone → fin del TTS → evaluar si volver a IDLE o LISTENING

El texto llega ya formateado por el backend como prosa natural apta para TTS
(sin markdown, sin listas, sin símbolos, números escritos en palabras).
```

---

## Bluetooth Low Energy (BLE): Comunicación con ESP32

### Configuración del Servicio BLE

```
Nombre del dispositivo ESP32: "RobotESP32"
Service UUID: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E

Características:
  TX (Android → ESP32, Write):
    UUID: 6E400002-B5A3-F393-E0A9-E50E24DCCA9E
    Properties: WRITE, WRITE_NO_RESPONSE
    Max: 512 bytes

  RX (ESP32 → Android, Notify):
    UUID: 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
    Properties: NOTIFY
    Frecuencia: Cada 1s (telemetría) o on-demand

Formato de todos los mensajes: JSON UTF-8
MTU negociado: 512 bytes
```

### Comandos que envía Android al ESP32 (TX)

```json
// Heartbeat — enviar cada 1 segundo via coroutine
// Si el ESP32 no recibe heartbeat en 3s → STOP automático + LEDs ámbar pulsante
{"type": "heartbeat", "timestamp": 1234567890}

// Movimento simple
{"type": "move", "direction": "forward", "speed": 70}
// direction: "forward" | "backward" | "rotate_left" | "rotate_right" | "stop"
// speed: 0-100

// Movimiento de búsqueda (girar ±90° buscando persona)
{
  "type": "move_sequence",
  "total_duration_ms": 2400,
  "steps": [
    {"direction": "rotate_right", "speed": 50, "duration_ms": 800},
    {"direction": "stop",         "speed": 0,  "duration_ms": 400},
    {"direction": "rotate_left",  "speed": 50, "duration_ms": 800},
    {"direction": "stop",         "speed": 0,  "duration_ms": 400}
  ]
}

// Stop inmediato
{"type": "stop"}

// Control de LEDs
{
  "type": "light",
  "action": "on",
  "color": "rgb(0, 200, 100)",
  "intensity": 80
}

// Solicitar telemetría de sensores y batería
{"type": "telemetry", "request": "sensors"}
```

### Telemetría que recibe Android del ESP32 (RX)

```json
// Telemetría periódica (cada 1s)
{
  "type": "telemetry",
  "battery": 75,
  "sensors": {
    "distance_front": 150,
    "distance_rear": 200,
    "cliff_detected": false,
    "light_level": 300
  },
  "timestamp": 1234567890
}

// Confirmación de comando ejecutado
{
  "status": "ok",
  "command_id": "<uuid>",
  "error_msg": ""
}
```

### Comportamiento de Heartbeat

```
- Android envía {"type": "heartbeat", "timestamp": ...} cada 1 segundo vía coroutine
- Si el ESP32 no recibe heartbeat en 3 segundos:
  → Motores: STOP inmediato
  → LEDs: ámbar pulsante (color de "cerebro desconectado")
  → No acepta nuevos comandos de movimiento hasta que se restaure el heartbeat
- Cuando el heartbeat se restaura: vuelve a operación normal
- Este mecanismo protege el hardware aunque la app Android se cuelgue o el SO la mate
```

---

## UI: Diseño y Layout

### Layout Landscape (pantalla completa)

```
Orientación: LANDSCAPE fija (nunca rota a vertical)
Fondo: #000000 (negro puro absoluto)
Modo: Immersive (sin barra de estado, sin barra de navegación)

┌─────────────────────────────────────────────────────────────────────┐
│ 🔋10% 🤖 [sup. izq — SOLO si batería robot ≤15%, parpadeante]   ⚡85% [sup. der — batería celular, siempre] │
│                                                                     │
│                    [EMOJI CENTRAL — 80% de la pantalla]             │
│                     centrado vertical y horizontal                  │
│                        con animación según estado                   │
│                                                                     │
│          [texto de respuesta — 10% de altura inferior]              │
│          azul claro metalizado · subtítulos de lo que dice Moji     │
└─────────────────────────────────────────────────────────────────────┘
```

### Colores

```
Fondo:                  #000000 (negro puro)
Texto de respuesta:     #88CCEE (azul claro metalizado)
  Fuente: monospace o sans-serif medium, legible en landscape
Indicador batería robot:  #FF3333 (rojo vivo), opacidad pulsante 0.4→1.0
  Solo visible cuando batería robot ≤ 15%
Indicador batería celular: #FFAA44 (naranja claro), opacidad pulsante 0.4→1.0
  Siempre visible (leer de BatteryManager del sistema Android)
```

### Animaciones por Estado

```
IDLE:         Parpadeo suave del emoji cada 3-5s
LISTENING:    Pulso de escala: 1.0 → 1.1 → 1.0 (300ms ciclo)
SEARCHING:    Rotación lenta del emoji (simulando escaneo visual)
THINKING:     Rotación suave del emoji (procesando)
RESPONDING:   La cara con emotion tag se muestra ANTES del TTS
              Durante la respuesta: hasta 3 emojis contextuales (de response_meta)
              Duración por emoji: 2000ms; Transición: fade | bounce | slide
ERROR:        Shake horizontal del emoji, 2s → vuelve a IDLE automáticamente
DISCONNECTED: Parpadeo lento del emoji 🔌
```

### Regla Crítica: Sin Botones de Control en Pantalla

No hay botones de control visibles para el usuario. El robot se controla **exclusivamente por voz**. Solo pueden existir botones de depuración invisibles en las esquinas (alpha ~0.01, solo para el desarrollador, eliminados en producción).

---

## Seguridad

### Certificate Pinning

```
El backend corre con un certificado TLS autofirmado.
La app debe implementar certificate pinning para aceptar solo ese certificado.

Configuración en network_security_config.xml:
<network-security-config>
  <domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">192.168.2.200</domain>
    <pin-set>
      <pin digest="SHA-256"><fingerprint-del-cert></pin>
    </pin-set>
  </domain-config>
</network-security-config>

El fingerprint del cert viene de AppPreferences (configurado en setup inicial).
OkHttp debe configurarse con CertificatePinner además del network_security_config.
```

### Almacenamiento Seguro (EncryptedSharedPreferences)

```
Usar EncryptedSharedPreferences con Android Keystore para:
  api_key                     : API key del backend
  backend_url                 : URL base (default: wss://192.168.2.200:9393)
  server_cert_fingerprint     : SHA-256 del certificado TLS del servidor
  device_id                   : UUID único del dispositivo (generado una vez)
  wake_word_sensitivity       : Float, default 0.7
  face_recognition_threshold  : Float, default 0.70
  face_search_timeout_ms      : Int, default 8000
  bluetooth_device_mac        : MAC del ESP32 (guardada tras primer emparejamiento)
  tts_language                : String, default "es"
  tts_speech_rate             : Float, default 0.9
  tts_pitch                   : Float, default 1.0
  last_sync                   : Long (timestamp del último sync con backend)
```

---

## Foreground Service y Watchdog

### Foreground Service (RobotService)

```
El RobotService es el núcleo operacional de Moji. Su responsabilidad:
  - Mantener el WakeWordDetector (Porcupine) activo 24/7
  - Mantener la conexión WebSocket con el backend (persistente, reconexión automática)
  - Gestionar la conexión BLE con el ESP32
  - Enviar heartbeat BLE cada 1s
  - Mostrar notificación persistente

Tipo: FOREGROUND_SERVICE_TYPE_MICROPHONE | FOREGROUND_SERVICE_TYPE_CAMERA

Notificación persistente:
┌───────────────────────────────────┐
│  🤖  Moji Robot                   │
│  Estado: Esperando comando        │
└───────────────────────────────────┘

Reinicio: START_STICKY
```

### Watchdog Externo (ServiceWatchdog)

```
El OS Android puede matar el foreground service en situaciones extremas.
START_STICKY no es suficiente. Por ello se implementa un watchdog externo:

Mecanismo: AlarmManager con alarma exacta cada 60 segundos
Implementación: BroadcastReceiver separado (independiente del servicio que supervisa)
Función: 
  1. AlarmManager dispara WatchdogReceiver cada 60s
  2. WatchdogReceiver verifica si RobotService está corriendo
  3. Si no está → startForegroundService(Intent(context, RobotService::class.java))
  4. Si está → no hacer nada
  5. Reprogramar siguiente alarma
Consumo: Despreciable (~0.1% batería/hora)
```

---

## Configuración de Compilación

```
minSdkVersion: 24 (Android 7.0)
targetSdkVersion: 33 (Android 13)
compileSdkVersion: 33

Activar:
  R8/ProGuard: true
  Multidex: true
  ViewBinding: true

Dependencias principales (build.gradle.kts):
  // Coroutines
  "org.jetbrains.kotlinx:kotlinx-coroutines-android"
  "androidx.lifecycle:lifecycle-runtime-ktx"
  "androidx.lifecycle:lifecycle-viewmodel-ktx"

  // CameraX
  "androidx.camera:camera-camera2"
  "androidx.camera:camera-lifecycle"
  "androidx.camera:camera-view"

  // ML Kit Face Detection
  "com.google.mlkit:face-detection"

  // TensorFlow Lite (FaceNet)
  "com.google.ai.edge.litert:litert"
  "com.google.ai.edge.litert:litert-support"

  // Wake Word
  "ai.picovoice:porcupine-android"

  // WebSocket + REST
  "com.squareup.okhttp3:okhttp"
  "com.squareup.retrofit2:retrofit"
  "com.squareup.retrofit2:converter-gson"

  // Emojis SVG
  "io.coil-kt:coil-svg"

  // Room (SQLite para embeddings)
  "androidx.room:room-runtime"
  "androidx.room:room-ktx"
  kapt("androidx.room:room-compiler")

  // Seguridad
  "androidx.security:security-crypto"

  // Android TextToSpeech — SIN dependencia externa (incluido en AOSP)
```

---

## Permisos Android (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

MainActivity en el manifiesto:
```xml
<activity
    android:name=".ui.MainActivity"
    android:screenOrientation="landscape"
    android:launchMode="singleTop"
    android:showWhenLocked="true"
    android:turnScreenOn="true">
```

---

## Flujo Completo de Activación (Wake Word → Respuesta)

```
1. IDLE: Porcupine escucha micrófono continuamente.

2. LISTENING (INMEDIATO): "Hey Moji" detectado.
   - UI actualiza cara a 👂 en el mismo thread (sin delay).
   - TTS interrumpido si estaba hablando.
   - AudioRecorder se activa.

3. SEARCHING: Cámara frontal activada.
   - Enviar a ESP32 vía BLE: rotate ±90° buscando persona.
   - ML Kit analiza frames a ~10 fps.

4a. Rostro detectado ANTES de 8s:
   - Enviar STOP al ESP32.
   - FaceNet extrae embedding 128D.
   - Comparar con Room SQLite.
   - Similitud > 0.70 → GREETING (conocida), enviar interaction_start con person_id.
   - Similitud ≤ 0.70 → REGISTERING (desconocida), enviar interaction_start con unknown + embedding.

4b. Timeout 8s sin rostro:
   - Enviar STOP al ESP32.
   - TTS: "No puedo verte. Por favor acércate al robot."
   - Volver a IDLE.

5. THINKING: Audio grabado → enviado al backend como binario tras interaction_start.
   - Grabar hasta 2s de silencio o 10s de timeout.
   - Enviar frames binarios AAC por WebSocket.
   - Enviar audio_end.

6. RESPONDING: Backend responde en streaming.
   - Recibir emotion → actualizar cara INMEDIATAMENTE.
   - Recibir text_chunks → acumular en buffer de oraciones → Android TTS habla.
   - Recibir capture_request → activar cámara → capturar → enviar de vuelta.
   - Recibir response_meta → mostrar secuencia emojis → enviar acciones al ESP32.
   - Recibir stream_end → interacción completa.

7. LISTENING (modo escucha continua 60s): Listo para siguiente frase sin wake word.

8. IDLE: Tras 60s de inactividad, volver a estado de reposo.
```

---

## Fase 0: Configuración Base y Dependencias

### Paso 0.1: Dependencias (`build.gradle.kts` del módulo app)
**Objetivo:** Preparar el proyecto con todas las bibliotecas requeridas.

**Instrucciones:**
Añadir al `build.gradle.kts` (app) todas las dependencias listadas en la sección "Configuración de Compilación" de este documento. Usar las versiones estables más recientes de cada biblioteca. Habilitar `viewBinding = true` en `buildFeatures` y `kapt` para Room. Verificar que el proyecto compila sin errores.

**Criterio de éxito:** El proyecto compila. Las clases de todas las librerías son accesibles desde el código Kotlin.

### Paso 0.2: Permisos y Manifiesto (`AndroidManifest.xml`)
**Objetivo:** Declarar todos los permisos necesarios y configurar la Activity principal.

**Instrucciones:**
1. Añadir todos los `<uses-permission>` de la sección "Permisos Android" de este documento.
2. Configurar `MainActivity` con `screenOrientation="landscape"`, `showWhenLocked`, `turnScreenOn`.
3. Crear `PermissionsActivity.kt` que solicite en cadena los permisos de runtime: `RECORD_AUDIO`, `CAMERA`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`. Si el usuario niega alguno → mostrar explicación y botón para abrir Settings del sistema.
4. `MainActivity` verifica al inicio si todos los permisos están concedidos. Si no → lanzar `PermissionsActivity`. Si sí → continuar.
5. Crear `network_security_config.xml` vacío (se completará en el paso de seguridad).

**Criterio de éxito:** La app pide los permisos uno a uno. Tras concederlos todos, muestra "Permisos OK" en pantalla negra landscape.

---

## Fase 1: Motor Visual y de Voz (Offline)

### Paso 1: Interfaz Visual (UI Inmersiva y Emojis)
**Objetivo:** La "cara del robot" en pantalla completa negra landscape.

**Instrucciones:**
1. Configurar `MainActivity` en Immersive Sticky Mode (ocultar System UI permanentemente).
2. Fondo de pantalla negro absoluto `#000000`.
3. Layout según diseño de la sección "UI: Diseño y Layout" de este documento:
    - `RobotFaceView` (ImageView) centrado, ~80% del ancho/alto. Muestra emoji SVG OpenMoji cargado con Coil.
    - `TextView` subtítulos en parte inferior (10% height): color `#88CCEE`, monospace.
    - `TextView` batería celular: esquina superior derecha, `#FFAA44`, pulsante (alpha 0.4→1.0 loop). Leer de `BatteryManager` del sistema.
    - `TextView` batería robot: esquina superior izquierda, `#FF3333`, pulsante. **Solo visible (`VISIBLE`) cuando batería robot ≤ 15%**. Inicialmente `GONE`.
4. Implementar `EmojiCache`: descarga SVGs de `https://openmoji.org/data/color/svg/<HEXCODE>.svg` con Coil, guarda en caché LRU en disco (50MB, `/cache/openmoji/`). Pre-cargar los 20 emojis listados en la sección "Cómo Cargar Emojis".
5. Implementar `RobotState` enum con todos los estados de la tabla "Máquina de Estados Completa", cada uno con su código Unicode de emoji.
6. Implementar `ExpressionManager`: dado un `EmotionTag` o un `RobotState`, retorna el código hexadecimal correspondiente (con selección aleatoria entre variantes para los emotion tags). Carga el SVG con Coil en `RobotFaceView`.
7. Implementar `StateManager` como singleton: `StateFlow<RobotState>`. El `MainActivity` observa este flow y actualiza emoji, animación y subtítulos.
8. Botones de debug invisibles (alpha `0.01f`) en esquinas: tocar esquina superior izquierda → ciclar estados; tocar esquina superior derecha → cargar emoji aleatorio.

**Criterio de éxito:** Pantalla 100% negra, landscape fija. Muestra 🤖 centrado. Tocando las esquinas, el emoji cambia. La batería del celular real aparece en la esquina derecha.

### Paso 2: Integración de Text-To-Speech (TTS)
**Objetivo:** Moji habla usando el motor TTS del sistema Android.

**Instrucciones:**
1. Implementar `AppPreferences` con `EncryptedSharedPreferences` para todas las claves de la sección "Almacenamiento Seguro" de este documento.
2. Implementar `TtsManager` con `TextToSpeech.OnInitListener`:
    - Leer idioma, velocidad (`0.9`) y tono (`1.0`) de `AppPreferences`.
3. Implementar `UtteranceProgressListener`:
    - `onStart` → notificar `StateManager` (animar cara con scale bounce 1.0 → 1.05 → 1.0).
    - `onDone` → callback para el caller.
4. `speak(text: String): Job` → solicitar foco de audio, reproducir, liberar foco al terminar. Retorna `Job` cancelable.
5. `speakChunked(flow: Flow<String>)` → acumula chunks hasta detectar fin de oración (`.`, `!`, `?`, `\n`) → llama a `speak()` con cada oración.

**Criterio de éxito:** Llamar a `ttsManager.speak("Hola, soy Moji")` → se escucha. La cara anima mientras habla. Logs muestran `onStart` y `onDone`.

---

## Fase 2: Escucha Activa

### Paso 3: Motor Wake Word (Porcupine) y Máquina de Estados
**Objetivo:** Moji despierta solo cuando escucha "Hey Moji".

**Instrucciones:**
1. Implementar `WakeWordDetector` con Porcupine:
    - Cargar `hey_moji_wake.ppn` desde `res/raw/`.
    - Sensibilidad `0.7` (desde `AppPreferences`).
    - Callback `onWakeWordDetected()`.
2. Al detectar wake word:
    - Cambiar `StateManager` a `LISTENING` **síncronamente e inmediatamente** (antes de cualquier async).
    - Interrumpir TTS si está activo (`ttsManager.stop()`).
    - Cancelar cualquier interacción activa.
3. El detector corre en un hilo dedicado de bajo consumo (<2% CPU).

**Criterio de éxito:** App inicia en IDLE. Al decir "Hey Moji" en voz alta, la cara cambia a 👂 en menos de 200ms. Si Moji estaba hablando, se calla.

### Paso 4: Captura de Audio y Detector de Silencio (VAD)
**Objetivo:** Grabar lo que dice el usuario y detenerse cuando deja de hablar.

**Instrucciones:**
1. Implementar `AudioRecorder`:
    - `AudioRecord` con 16000Hz, mono, PCM 16-bit, buffer 1024 frames.
    - Se activa cuando el estado es `LISTENING`.
2. Calcular RMS de cada frame continuamente. Detectar silencio: 2s consecutivos de RMS bajo umbral → fin de grabación. Timeout máximo: 10s.
3. Al terminar: comprimir PCM a AAC (MediaCodec), cambiar estado a `THINKING`, emitir `ByteArray` para que el WebSocket lo envíe.

**Criterio de éxito:** Tras "Hey Moji", el usuario habla y guarda silencio. Exactamente 2s después: logs "Audio capturado: X bytes", UI muestra 🤔.

---

## Fase 3: Visión y Reconocimiento de Personas (On-Device)

### Paso 5: Cámara Activa y Detección de Rostros (ML Kit)
**Objetivo:** Activar la cámara frontal silenciosamente y detectar rostros.

**Instrucciones:**
1. Implementar `CameraManager` con CameraX:
    - **Solo** `LENS_FACING_FRONT` (nunca la trasera). Sin `PreviewView` en la UI.
    - `ImageAnalysis` a ~10 fps.
2. Implementar `FaceDetector` con ML Kit:
    - Configuración rápida (sin landmarks, solo bounding boxes).
    - Callbacks: `onFaceDetected(boundingBox, frame)` y `onNoFace()`.
3. Al entrar en `SEARCHING`:
    - Activar `CameraManager` + `FaceDetector`.
    - Iniciar timer de `face_search_timeout_ms` (8000ms, desde `AppPreferences`).
    - Enviar comando de búsqueda al ESP32 (cuando BLE esté implementado en Paso 8; por ahora loguear).
4. Si `onFaceDetected` antes del timeout → cancelar timer → proceder al Paso 6.
5. Si timeout → TTS: "No puedo verte. Por favor acércate al robot." → estado `IDLE`.

**Criterio de éxito:** Tapar la cámara frontal y decir "Hey Moji" → a los 8s dice "No puedo verte". Sin taparla → detecta un rostro en <1s y frena el timer.

### Paso 6: Reconocimiento de Personas (FaceNet Embeddings + Room SQLite)
**Objetivo:** Identificar si la persona es conocida o nueva.

**Instrucciones:**
1. Implementar `FaceNetModel`:
    - Cargar `facenet.tflite` desde `assets/` con TFLite `Interpreter`.
    - Input: crop del bounding box redimensionado a 112×112px, normalizado a `[-1, 1]`.
    - Output: vector float32 de 128D, normalizado L2.
2. Implementar `FaceEmbeddingStore` con Room (esquema de la sección "Esquema Room Database"):
    - DAO: `insertEmbedding()`, `getAllEmbeddings()`, `getByPersonId()`, `updateLastSeen()`.
3. Implementar `FaceSimilarityEngine`:
    - `findBestMatch(query: FloatArray): FaceMatch?`
    - Similitud coseno con todos los embeddings en Room.
    - Si mejor > 0.70 → retornar `FaceMatch(personId, name, score)`. Si no → `null`.
4. Implementar `FaceRecognitionManager` orquestando los pasos 1-3.
5. `GreetingOrchestrator` procesa el resultado:
    - Match → estado `GREETING`, preparar `interaction_start` con `person_id`.
    - No match → estado `REGISTERING`, preparar `interaction_start` con `"unknown"` + embedding base64.

**Criterio de éxito:**
- 1ª prueba: "Embedding generado. Similitud < 0.70. Persona desconocida."
- 2ª prueba (misma cara): "Similitud > 0.85 con persona X. Reconocida."

---

## Fase 4: Cerebro de Moji (Backend WebSocket)

### Paso 7: Cliente WebSocket y Flujo de Mensajería Completo
**Objetivo:** Conectar con el backend y gestionar todo el protocolo de mensajes.

**Instrucciones:**
1. Implementar `CertificatePinner` con OkHttp: pinning del fingerprint del cert TLS desde `AppPreferences`.
2. Implementar `RobotWebSocketClient` con OkHttp `WebSocket`:
    - URL desde `AppPreferences` (`wss://192.168.2.200:9393/ws/interact`).
    - Primer mensaje tras conectar: `auth` con api_key y device_id.
    - Reconexión: backoff exponencial (1s → 2s → 4s → 8s → máx 30s).
    - Desconexión → estado `DISCONNECTED`.
3. Implementar `WsMessageParser`: parsea cada mensaje JSON a sealed classes:
   `AuthOk`, `PersonRegistered`, `EmotionMessage`, `TextChunk`, `CaptureRequest`, `ResponseMeta`, `FaceScanActions`, `StreamEnd`, `ErrorMessage`.
4. Implementar envíos completos según la sección "Mensajes que envía Android → Backend".
5. Implementar recepción completa en orden (sección "Mensajes que recibe Android ← Backend"):
    - `EmotionMessage` → `ExpressionManager.showEmotion(tag)` **inmediatamente**.
    - `TextChunk` → `ttsManager.speakChunked(text)`.
    - `CaptureRequest` → activar cámara → capturar foto/video → enviar `image` o `video` WS.
    - `ResponseMeta` → mostrar secuencia emojis contextuales + loguear acciones ESP32 (BLE en Paso 8).
    - `StreamEnd` → interacción completa → iniciar escucha continua 60s.
    - `PersonRegistered` → `faceEmbeddingStore.insertEmbedding(personId, name, lastCapuredEmbedding)`.
6. Modo escucha continua: countdown 60s. Si el usuario habla → detectar 2s silencio → enviar nuevo audio sin wake word → reiniciar countdown. Si pasan 60s → `IDLE`.

**Criterio de éxito:** La app conecta al backend. Decir "Hey Moji" + pregunta → el emoji cambia según emotion tag → el TTS reproduce la respuesta → tras la respuesta se puede hablar de nuevo sin wake word.

---

## Fase 5: Movimiento y Resiliencia del Sistema

### Paso 8: Conexión BLE con ESP32
**Objetivo:** Control físico del robot vía Bluetooth Low Energy.

**Instrucciones:**
1. Implementar `BluetoothManager`:
    - Escanear con `BluetoothLeScanner` buscando dispositivo `"RobotESP32"`.
    - Guardar MAC en `AppPreferences` tras primer emparejamiento. Reconectar automáticamente si el dispositivo está en rango.
2. Conectar GATT con los UUIDs de la sección "Configuración del Servicio BLE" de este documento.
3. Implementar `ESP32Protocol`: serializa comandos a JSON UTF-8 y los envía por la característica TX.
4. Implementar `HeartbeatSender`: coroutine que cada 1000ms envía `{"type": "heartbeat", "timestamp": ...}` al ESP32. Se cancela si BLE desconecta.
5. Suscribirse a notificaciones RX: parsear telemetría → actualizar batería robot en `StateManager` → mostrar/ocultar indicador UI.
6. Integrar con los estados:
    - `SEARCHING` → enviar secuencia búsqueda (rotate_right → stop → rotate_left → stop).
    - Rostro detectado → enviar `stop`.
    - `ResponseMeta.actions` → parsear y enviar primitivas ESP32.

**Criterio de éxito:** El teléfono conecta al ESP32 automáticamente. La consola del ESP32 muestra "Heartbeat" cada segundo. Al decir "Hey Moji", el robot físico rota buscando la cara y para cuando la encuentra.

### Paso 9: Foreground Service + Watchdog (Inmortalidad)
**Objetivo:** Moji funciona 24/7 sin ser matado por Android.

**Instrucciones:**
1. Mover toda la lógica core a `RobotService` (Foreground Service):
    - `WakeWordDetector`, `RobotWebSocketClient`, `BluetoothManager`, `HeartbeatSender`, `CameraManager`, `AudioRecorder`.
    - `StateManager` como singleton accesible desde toda la app.
2. `startForeground()` con la notificación persistente de la sección "Foreground Service" de este documento.
3. Flags: `FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_CAMERA`.
4. `onStartCommand` → retornar `START_STICKY`.
5. Implementar `ServiceWatchdog` (BroadcastReceiver):
    - Registrar con `AlarmManager` para disparar cada 60s.
    - En `onReceive`: si `RobotService` no está running → `startForegroundService()`.
    - Reprogramar siguiente alarma antes de salir.
6. Arrancar el watchdog desde `MainActivity.onCreate()` y desde un `BOOT_COMPLETED` BroadcastReceiver.
7. `MainActivity` se une al servicio vía `bindService()` para observar `StateFlow` y actualizar la UI.
8. Wake word detectado en background → el servicio lanza la Activity con `FLAG_ACTIVITY_REORDER_TO_FRONT`.

**Criterio de éxito:**
1. App abierta → "Hey Moji" → funciona.
2. App cerrada (home) → "Hey Moji" → la app vuelve al frente y responde.
3. App matada desde recientes → esperar ≤60s → "Hey Moji" → el watchdog la relanzó y responde.
4. La consola del ESP32 muestra heartbeat continuo incluso con la app en background.