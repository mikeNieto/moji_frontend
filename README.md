# Moji Frontend

Aplicación Android para el proyecto Moji.

## Configuración inicial

### `local.properties`

El archivo `local.properties` está excluido de Git (`.gitignore`) porque contiene rutas locales y claves sensibles. Al clonar el proyecto por primera vez, Android Studio genera automáticamente este archivo con la ruta del SDK, pero **debes agregar manualmente** las siguientes propiedades:

```properties
# Porcupine Wake Word API Key
# Obtener en: https://console.picovoice.ai/
PORCUPINE_ACCESS_KEY=tu_access_key_aqui
```

#### Ejemplo completo de `local.properties`

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk

# Porcupine Wake Word API Key
PORCUPINE_ACCESS_KEY=tu_access_key_aqui
```

> **Nota:** La `PORCUPINE_ACCESS_KEY` se inyecta en el código a través de `BuildConfig.PORCUPINE_ACCESS_KEY` desde `app/build.gradle.kts`. Sin esta clave, la funcionalidad de wake word no funcionará.

## Cómo obtener la API Key de Porcupine

1. Crear una cuenta en [Picovoice Console](https://console.picovoice.ai/)
2. Ir a la sección **AccessKey**
3. Copiar la key y pegarla como valor de `PORCUPINE_ACCESS_KEY` en `local.properties`

