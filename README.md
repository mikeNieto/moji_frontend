# Moji Frontend

Aplicación Android para el proyecto Moji.

## Configuración inicial

### `local.properties`

El archivo `local.properties` está excluido de Git (`.gitignore`) porque contiene rutas locales y claves sensibles. Al clonar el proyecto por primera vez, Android Studio genera automáticamente este archivo con la ruta del SDK, pero **debes agregar manualmente** las siguientes propiedades:

```properties
# Porcupine Wake Word API Key
# Obtener en: https://console.picovoice.ai/
PORCUPINE_ACCESS_KEY=tu_access_key_aqui

# SHA-256 fingerprint (Base64) del certificado TLS autofirmado del backend Moji
# El valor es el campo "Base64" del output del script de generación de certificados
SERVER_CERT_FINGERPRINT=fingerprint_base64_aqui
```

#### Ejemplo completo de `local.properties`

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk

# Porcupine Wake Word API Key
PORCUPINE_ACCESS_KEY=tu_access_key_aqui

# SHA-256 fingerprint del certificado TLS del backend (autofirmado, válido hasta 2036)
SERVER_CERT_FINGERPRINT=5S9mQO4Fxo4W+l1Cz3rg45sgvMFE2xAhmmBwvaGuR3I=
```

> **Nota:** La `PORCUPINE_ACCESS_KEY` se inyecta en el código a través de `BuildConfig.PORCUPINE_ACCESS_KEY` desde `app/build.gradle.kts`. Sin esta clave, la funcionalidad de wake word no funcionará.

> **Nota:** El `SERVER_CERT_FINGERPRINT` se inyecta como `BuildConfig.SERVER_CERT_FINGERPRINT` y lo usa `RobotWebSocketClient` para hacer certificate pinning sobre la conexión TLS con el backend. Sin este valor, la app aceptará cualquier certificado (solo válido en desarrollo).

## Cómo obtener la API Key de Porcupine

1. Crear una cuenta en [Picovoice Console](https://console.picovoice.ai/)
2. Ir a la sección **AccessKey**
3. Copiar la key y pegarla como valor de `PORCUPINE_ACCESS_KEY` en `local.properties`

## Cómo obtener el fingerprint del certificado del backend

El script de generación de certificados del servidor imprime automáticamente el fingerprint en formato Base64 al final de su ejecución. Busca la línea:

```
Base64     : <valor>
```

También puedes calcularlo manualmente desde el archivo `.pem` del servidor:

```bash
openssl s_client -connect 192.168.2.200:9393 </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | base64
```
