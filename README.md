# Grabador de Llamadas

App de Android hecha en Flutter para grabar pantalla + audio durante llamadas y
videoconferencias (WhatsApp, Zoom, Meet, etc.), algo que el grabador de pantalla nativo de
Android no hace.

## Cómo funciona

Android bloquea a nivel de plataforma la captura directa del audio interno de una llamada
(`AudioPlaybackCapture` excluye explícitamente el audio marcado como
`USAGE_VOICE_COMMUNICATION`). Esta app no intenta saltarse esa restricción ni requiere root:
usa dos APIs públicas sin restricciones especiales de privacidad.

- **Video**: `MediaProjection`, capturando el contenido de la pantalla.
- **Audio**: `MediaRecorder.AudioSource.MIC`, el micrófono físico. Con el **altavoz
  activado** durante la llamada, el micrófono capta acústicamente ambas voces.

El resultado se guarda como `.mp4` en `Películas/GrabadorLlamadas`, visible directamente en
la Galería.

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
- Activar el altavoz durante la llamada para que se grabe la voz de la otra persona

## Ejecutar

```
flutter pub get
flutter run
```
