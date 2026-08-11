# Punto de referencia: captura de audio en llamadas

**Estado: funcionando, confirmado con prueba real.** 2026-08-11, grabación `grabacion_20260811_110952.mp4`
(57.8s, en una Meet real): `mean_volume: -31.5 dB`, `max_volume: -3.2 dB` (audio real, no silencio —
compárese con una prueba fallida anterior donde ambos valores daban `-91.0 dB`, silencio digital).

Este documento existe porque llegar a este punto costó **cinco rondas de arreglos** y agotar varias
vías que no funcionaron. Si en el futuro el audio de las llamadas deja de grabarse, empieza por leer
esto antes de tocar código: probablemente algo de lo que se describe abajo se rompió (actualización de
Android, restablecimiento de fábrica, cambio de equipo, actualización de Meet), no un bug nuevo.

## Qué hace que funcione hoy (las 3 piezas)

Todo vive en `android/app/src/main/kotlin/com/cristianbravo/grabador_llamadas/CallRecordingPipeline.kt`,
que reemplazó a `MediaRecorder` por `MediaCodec` + `MediaMuxer` manual porque `MediaRecorder` no permite
mezclar dos fuentes de audio.

### 1. El servicio de accesibilidad (`RecorderAccessibilityService.kt`) — la pieza que más importa

Android silencia por completo el micrófono de **cualquier app** mientras otra app (Meet, WhatsApp, Zoom)
mantiene su propia sesión de audio "privacy-sensitive" (`AudioSource.VOICE_COMMUNICATION`/`CAMCORDER`).
La única excepción documentada en AOSP es un servicio de accesibilidad. `RecorderAccessibilityService`
no hace nada con los eventos — existe solo para calificar para esa excepción.

**Requiere activación manual, una sola vez, ANTES de entrar a una llamada:**
Ajustes > Accesibilidad > Grabador de Llamadas > activar (o desde el botón en la app, que abre esa
pantalla). **Importante:** Android bloquea el interruptor con el mensaje "No disponible durante
llamadas" si ya estás en una videollamada al intentar activarlo — hay que hacerlo con la llamada
cerrada. Si el usuario restablece el teléfono, reinstala la app desde cero, o cambia de equipo, hay que
volver a activarlo a mano; no hay forma de hacerlo por código.

Se puede confirmar en código si está activo con el método `isAccessibilityServiceEnabled` de
`MainActivity.kt` (lee `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`).

### 2. Forzar `MODE_IN_COMMUNICATION` + fuente `VOICE_RECOGNITION`

En `CallRecordingPipeline.forceInCommunicationMode()` y `setupAudioCapture()`: antes de abrir el
micrófono, se fuerza `AudioManager.mode = MODE_IN_COMMUNICATION` (configuración GLOBAL del dispositivo,
se restaura al detener en `restoreAudioMode()`), y se usa `AudioSource.VOICE_RECOGNITION` en vez de
`AudioSource.MIC`. Es el truco que documenta la app Cube ACR para Android 9-14. **No hay garantía de
por qué funciona exactamente ni de que siga funcionando** — es un comportamiento no documentado
oficialmente, distinto de la excepción de accesibilidad (que sí está en AOSP).

No sabemos con certeza si hace falta esto ADEMÁS del servicio de accesibilidad, o si el de accesibilidad
solo ya bastaría en este equipo — no se probó esa combinación por separado. Si en el futuro hay que
depurar, vale la pena probar quitando esto y dejando solo el servicio de accesibilidad, para aislar cuál
pieza es la que realmente sostiene la captura.

### 3. Captura de audio interno (`AudioPlaybackCaptureConfiguration`) — solo sirve fuera de llamadas VoIP

Captura lo que reproducen otras apps con `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`. **Confirmado por
prueba real que Meet, WhatsApp y Zoom quedan fuera de esto**: marcan su audio de llamada (incluida la
voz que reproducen del interlocutor) con una clasificación que esta API no puede capturar. Sigue en el
código porque no hace daño (si no hay nada que capturar, simplemente no aporta nada a la mezcla) y sí
sirve para grabaciones de pantalla que no sean una videollamada (un video de YouTube, un juego, etc.).

**No confíes en esta pieza para la voz del interlocutor en una llamada — no funciona.** Toda la voz que
se graba en una llamada es la TUYA, captada por el micrófono gracias a las piezas 1 y 2. La voz de la
otra persona no se puede grabar por software sin root en este tipo de apps (restricción de plataforma,
no bug — ver README.md).

## Bugs ya resueltos (para no repetirlos)

- **Crash inmediato al grabar**: `BufferOverflowException` al escribir en el buffer de entrada del
  codificador de audio sin haber fijado `KEY_MAX_INPUT_SIZE`. Arreglado fijando ese valor y, sobre todo,
  escribiendo en `feedAudioEncoder()` en trozos según `inputBuffer.remaining()` en vez de un tamaño fijo.
- **Video "congelado" con duración de horas en vez de segundos**: el video trae su `presentationTimeUs`
  basado en el reloj de actividad del dispositivo (un número enorme), mientras el audio arranca en 0.
  Arreglado restando un offset (`videoPtsOffsetUs` en `drainVideoEncoder()`) para que el video también
  arranque cerca de 0.
- **Un fallo en los hilos de video/audio tumbaba toda la app**: una excepción no capturada en un hilo
  en segundo plano mata todo el proceso en Android por defecto. Se agregó un `try/catch` alrededor de
  `runVideoLoop()`/`runAudioLoop()` en `start()` que corta la grabación en silencio en vez de crashear,
  y se agregó logging (`Log.e`/`Log.w`, tag `CallRecordingPipeline`) para poder diagnosticar por logcat.

## Cómo diagnosticar si esto se rompe en el futuro

Con el teléfono conectado por USB (depuración habilitada):

```bash
# 1. Limpiar y capturar logcat mientras se reproduce el problema
adb logcat -c
adb logcat -v time > logcat.txt &
# ... reproducir el problema en el teléfono ...

# 2. Buscar errores propios (todo lo nuestro pasa por este tag)
grep -i "CallRecordingPipeline" logcat.txt

# 3. Bajar el archivo grabado más reciente
adb shell "ls -la /storage/emulated/0/Movies/GrabadorLlamadas/"
adb pull /storage/emulated/0/Movies/GrabadorLlamadas/<archivo>.mp4 .

# 4. Inspeccionar pistas y duración
ffprobe -v error -show_entries format=duration:stream=index,codec_type,codec_name -of default=noprint_wrappers=0 <archivo>.mp4

# 5. Medir si el audio tiene señal real o es silencio digital
ffmpeg -i <archivo>.mp4 -af volumedetect -f null /dev/null 2>&1 | grep -E "mean_volume|max_volume"
```

Si `mean_volume`/`max_volume` rondan **-90 dB o menos**, es silencio real (nada capturado) — revisar
piezas 1 y 2. Si el video tiene muy pocos frames para su duración o `ffprobe` muestra una duración muy
distinta a la real, revisar el offset de timestamps (pieza ya resuelta arriba, por si se reintrodujo).

## Qué comprobar primero si deja de funcionar

1. ¿Sigue activado el servicio de accesibilidad? (Ajustes > Accesibilidad > Grabador de Llamadas). Se
   desactiva solo al restablecer el equipo o reinstalar la app desde cero.
2. ¿Cambiaste de teléfono o de versión de Android? El truco de `MODE_IN_COMMUNICATION` +
   `VOICE_RECOGNITION` no está documentado oficialmente y puede depender del fabricante/versión.
3. ¿Se actualizó la app Meet/WhatsApp/Zoom? Podrían cambiar cómo manejan su sesión de audio.
4. Repetir el diagnóstico de la sección anterior antes de asumir que es un bug de código nuevo.

## Archivos involucrados

- `android/app/src/main/kotlin/com/cristianbravo/grabador_llamadas/CallRecordingPipeline.kt` — el motor.
- `android/app/src/main/kotlin/com/cristianbravo/grabador_llamadas/RecorderAccessibilityService.kt` —
  servicio vacío, solo para la exención.
- `android/app/src/main/kotlin/com/cristianbravo/grabador_llamadas/ScreenRecordService.kt` — orquesta
  el ciclo de vida (usa el pipeline en vez de `MediaRecorder` directo).
- `android/app/src/main/kotlin/com/cristianbravo/grabador_llamadas/MainActivity.kt` — puente con
  Flutter: `isAccessibilityServiceEnabled`, `openAccessibilitySettings`.
- `android/app/src/main/res/xml/accessibility_service_config.xml` y
  `android/app/src/main/res/values/strings.xml` — configuración y descripción del servicio.
- `android/app/src/main/AndroidManifest.xml` — registro del servicio de accesibilidad.
- `lib/main.dart` — botón y aviso en pantalla para activar el servicio.
