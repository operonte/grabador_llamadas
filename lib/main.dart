import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Grabador de Llamadas',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const RecorderPage(),
    );
  }
}

class RecorderPage extends StatefulWidget {
  const RecorderPage({super.key});

  @override
  State<RecorderPage> createState() => _RecorderPageState();
}

class _RecorderPageState extends State<RecorderPage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('grabador_llamadas/screen_record');

  bool _isRecording = false;
  bool _isBusy = false;
  bool _hasOverlayPermission = false;
  Timer? _ticker;
  DateTime? _startedAt;
  Duration _elapsed = Duration.zero;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkOverlayPermission();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _ticker?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // La grabación puede haberse detenido desde el botón de la notificación
    // mientras la app estaba en segundo plano; al volver, sincronizamos.
    if (state == AppLifecycleState.resumed) {
      _syncStateWithNative();
      _checkOverlayPermission();
    }
  }

  Future<void> _checkOverlayPermission() async {
    final granted = await _channel.invokeMethod<bool>('canDrawOverlays') ?? false;
    if (!mounted || granted == _hasOverlayPermission) return;
    setState(() => _hasOverlayPermission = granted);
  }

  Future<void> _syncStateWithNative() async {
    final recording = await _channel.invokeMethod<bool>('isRecording') ?? false;
    if (!mounted || recording == _isRecording) return;
    setState(() => _isRecording = recording);
    if (recording) {
      _startTicker();
    } else {
      _stopTicker();
    }
  }

  void _startTicker() {
    _startedAt = DateTime.now();
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _elapsed = DateTime.now().difference(_startedAt!));
    });
  }

  void _stopTicker() {
    _ticker?.cancel();
    _ticker = null;
    setState(() => _elapsed = Duration.zero);
  }

  String _formatElapsed(Duration d) {
    final minutes = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  Future<void> _toggleRecording() async {
    if (_isRecording) {
      await _channel.invokeMethod('stopRecording');
      _stopTicker();
      setState(() => _isRecording = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Grabación guardada en Películas/GrabadorLlamadas')),
        );
      }
      return;
    }

    setState(() => _isBusy = true);

    final micGranted = await _channel.invokeMethod<bool>('requestPermissions') ?? false;

    if (!micGranted) {
      setState(() => _isBusy = false);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Se necesita permiso de micrófono para grabar'),
        ),
      );
      return;
    }

    try {
      final started = await _channel.invokeMethod<bool>('startRecording') ?? false;
      setState(() {
        _isRecording = started;
        _isBusy = false;
      });
      if (started) {
        _startTicker();
      } else if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No se otorgó permiso para grabar la pantalla')),
        );
      }
    } on PlatformException {
      setState(() => _isBusy = false);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Ocurrió un error al iniciar la grabación')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Grabador de Llamadas')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _isRecording ? Icons.fiber_manual_record : Icons.videocam_outlined,
              size: 96,
              color: _isRecording ? Colors.red : Colors.grey,
            ),
            const SizedBox(height: 24),
            Text(
              _isRecording ? 'Grabando… ${_formatElapsed(_elapsed)}' : 'Detenido',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            if (!_isRecording) ...[
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  'Activa el altavoz durante la llamada para que también '
                  'se grabe la voz de la otra persona.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              ),
              TextButton(
                onPressed: () => _channel.invokeMethod('openBatterySettings'),
                child: const Text('¿Se corta en llamadas largas? Ajustar batería'),
              ),
              if (!_hasOverlayPermission)
                TextButton(
                  onPressed: () async {
                    await _channel.invokeMethod('requestOverlayPermission');
                  },
                  child: const Text('Activar controles flotantes (opcional)'),
                ),
            ] else
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  _hasOverlayPermission
                      ? 'Usa la burbuja flotante para pausar o detener sin salir de la llamada.'
                      : 'Puedes detenerla desde la notificación sin volver a la app.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.grey),
                ),
              ),
            const SizedBox(height: 40),
            _isBusy
                ? const CircularProgressIndicator()
                : FilledButton.icon(
                    onPressed: _toggleRecording,
                    icon: Icon(_isRecording ? Icons.stop : Icons.fiber_manual_record),
                    label: Text(_isRecording ? 'Detener' : 'Grabar'),
                    style: FilledButton.styleFrom(
                      backgroundColor: _isRecording ? Colors.red : null,
                      minimumSize: const Size(200, 56),
                    ),
                  ),
          ],
        ),
      ),
    );
  }
}
