import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Único punto de acceso al canal nativo de grabación. Antes el nombre del
/// canal estaba duplicado como literal en main.dart, recordings_page.dart y
/// MainActivity.kt — cambiarlo significaba editar los tres en sincronía. Esta
/// clase es además la forma que tomaría la API pública si este proyecto se
/// convierte en un paquete de Flutter más adelante.
class ScreenRecorder {
  ScreenRecorder._();

  static const MethodChannel _channel = MethodChannel('grabador_llamadas/screen_record');

  // Métodos "de estado" (abrir ajustes, consultar un permiso, detener): una
  // PlatformException acá no tiene una recuperación distinta por caller, así
  // que se absorbe en un solo lugar en vez de repetir try/catch en cada botón
  // de main.dart. startRecording/requestPermissions quedan afuera a propósito:
  // sus fallos sí cambian el flujo de la UI y ya se manejan en la pantalla.
  static Future<bool> _guardBool(String method) async {
    try {
      return await _channel.invokeMethod<bool>(method) ?? false;
    } on PlatformException catch (e) {
      debugPrint('ScreenRecorder.$method falló: $e');
      return false;
    }
  }

  static Future<void> _guardVoid(String method) async {
    try {
      await _channel.invokeMethod(method);
    } on PlatformException catch (e) {
      debugPrint('ScreenRecorder.$method falló: $e');
    }
  }

  static Future<bool> requestPermissions() async =>
      await _channel.invokeMethod<bool>('requestPermissions') ?? false;

  static Future<bool> startRecording() async =>
      await _channel.invokeMethod<bool>('startRecording') ?? false;

  static Future<void> stopRecording() => _guardVoid('stopRecording');

  static Future<bool> isRecording() async =>
      await _channel.invokeMethod<bool>('isRecording') ?? false;

  static Future<bool> canDrawOverlays() => _guardBool('canDrawOverlays');

  static Future<void> requestOverlayPermission() => _guardVoid('requestOverlayPermission');

  static Future<void> openBatterySettings() => _guardVoid('openBatterySettings');

  static Future<bool> isAccessibilityServiceEnabled() => _guardBool('isAccessibilityServiceEnabled');

  static Future<void> openAccessibilitySettings() => _guardVoid('openAccessibilitySettings');

  static Future<List<Map<dynamic, dynamic>>> listRecordings() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('listRecordings') ?? [];
    return raw.cast<Map<dynamic, dynamic>>();
  }

  static Future<bool> openRecording(String uri) async =>
      await _channel.invokeMethod<bool>('openRecording', {'uri': uri}) ?? false;

  static Future<bool> shareRecording(String uri) async =>
      await _channel.invokeMethod<bool>('shareRecording', {'uri': uri}) ?? false;

  static Future<bool> deleteRecording(String uri) async =>
      await _channel.invokeMethod<bool>('deleteRecording', {'uri': uri}) ?? false;
}
