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

class _RecorderPageState extends State<RecorderPage> {
  static const _channel = MethodChannel('grabador_llamadas/screen_record');

  bool _isRecording = false;
  bool _isBusy = false;

  Future<void> _toggleRecording() async {
    if (_isRecording) {
      await _channel.invokeMethod('stopRecording');
      setState(() => _isRecording = false);
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
      if (!started && mounted) {
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
              _isRecording ? 'Grabando…' : 'Detenido',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            if (!_isRecording)
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  'Activa el altavoz durante la llamada para que también '
                  'se grabe la voz de la otra persona.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              )
            else
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  'El video se guarda en Películas/GrabadorLlamadas.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
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
