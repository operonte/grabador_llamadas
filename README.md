# Grabador de Llamadas

App de Android hecha en Flutter para grabar pantalla + audio durante llamadas y
videoconferencias (WhatsApp, Zoom, Meet, etc.), algo que el grabador de pantalla nativo de
Android no hace.

## Cómo funciona

El audio se arma mezclando dos fuentes con `MediaCodec`/`MediaMuxer` (no `MediaRecorder`,
que no permite combinarlas):

- **Voz del interlocutor**: `AudioPlaybackCaptureConfiguration` capturando el audio que
  reproduce la otra app. **Confirmado por prueba real que esto NO funciona con apps de
  videollamada** (Meet, WhatsApp, Zoom): estas marcan su audio de llamada con una
  clasificación distinta a `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN` (las únicas que esta
  API puede capturar), así que la voz del interlocutor no se grava en una llamada de este
  tipo. Sí sirve para audio de apps normales (reproductor de música, video) fuera de una
  llamada.
- **Tu voz**: `AudioSource.MIC`, el micrófono físico. Mientras la llamada mantenga su
  propia sesión de micrófono activa (`VOICE_COMMUNICATION`/`CAMCORDER`, "privacy-sensitive"),
  Android silencia por completo el `MIC` de cualquier otra app — **salvo el de un servicio
  de accesibilidad**. Por eso existe `RecorderAccessibilityService`: no hace nada con los
  eventos de accesibilidad, solo existe para calificar para esa excepción y que el
  micrófono de esta app no se silencie durante la llamada. Hay que habilitarlo a mano en
  Ajustes > Accesibilidad (botón en la pantalla principal). Es una vía inestable: puede
  variar por fabricante/versión de Android y dejar de funcionar con una actualización; y
  Google Play prohíbe publicar apps que usen este truco (no aplica acá porque no se
  publica). Fuera de una llamada activa, el micrófono funciona con normalidad sin necesitar
  nada de esto.
- **Video**: `MediaProjection`, capturando el contenido de la pantalla.

El resultado se guarda como `.mp4` en `Películas/GrabadorLlamadas`, visible directamente en
la Galería.

### Limitación conocida

No existe hoy una forma confirmada de grabar la voz del interlocutor en una videollamada
sin root. Alternativas si eso es lo que necesitas: grabación nativa de Meet (cuenta Google
Workspace), un grabador externo apuntando al parlante en modo altavoz, o unirte a la
reunión desde una laptop (ahí grabar audio del sistema sí funciona sin esta restricción).

## Gestión de grabaciones

Desde el ícono de la barra superior se accede a "Mis grabaciones": lista todo lo grabado
(fecha, duración, tamaño) y permite reproducir, compartir o borrar cada video sin salir de
la app ni depender de la Galería del sistema.

## Controles durante la grabación

- **Notificación**: botón "Detener" sin necesidad de volver a la app.
- **Burbuja flotante (opcional)**: si activas "Dibujar sobre otras apps" desde la pantalla
  principal, aparece una burbuja arrastrable con pausar/reanudar y detener mientras grabas,
  sin salir de la llamada. Si no la activas, la app funciona igual, solo sin burbuja.

## Requisitos

- Flutter 3.44+
- Android 10 (API 29) o superior

## Ejecutar

```
flutter pub get
flutter run
```
