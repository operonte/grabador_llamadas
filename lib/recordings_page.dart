import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'duration_format.dart';
import 'screen_recorder.dart';

class Recording {
  final String uri;
  final String name;
  final DateTime dateAdded;
  final Duration duration;
  final int sizeBytes;

  Recording({
    required this.uri,
    required this.name,
    required this.dateAdded,
    required this.duration,
    required this.sizeBytes,
  });

  factory Recording.fromMap(Map<dynamic, dynamic> map) {
    return Recording(
      uri: map['uri'] as String? ?? '',
      name: map['name'] as String? ?? 'grabacion.mp4',
      dateAdded: DateTime.fromMillisecondsSinceEpoch(
        ((map['dateAddedSeconds'] as int?) ?? 0) * 1000,
      ),
      duration: Duration(milliseconds: map['durationMs'] as int? ?? 0),
      sizeBytes: map['sizeBytes'] as int? ?? 0,
    );
  }

  String get formattedDuration => formatDuration(duration);

  String get formattedSize {
    final mb = sizeBytes / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
  }

  String get formattedDate {
    final d = dateAdded;
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(d.day)}/${two(d.month)}/${d.year} ${two(d.hour)}:${two(d.minute)}';
  }
}

class RecordingsPage extends StatefulWidget {
  const RecordingsPage({super.key});

  @override
  State<RecordingsPage> createState() => _RecordingsPageState();
}

class _RecordingsPageState extends State<RecordingsPage> {
  List<Recording>? _recordings;
  bool _loadFailed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loadFailed = false);
    try {
      final raw = await ScreenRecorder.listRecordings();
      if (!mounted) return;
      setState(() {
        _recordings = raw.map((e) => Recording.fromMap(e)).toList();
      });
    } on PlatformException {
      if (!mounted) return;
      setState(() => _loadFailed = true);
    }
  }

  Future<void> _delete(Recording recording) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('¿Borrar esta grabación?'),
        content: Text(recording.name),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Borrar')),
        ],
      ),
    );
    if (confirmed != true) return;

    final ok = await ScreenRecorder.deleteRecording(recording.uri);
    if (ok) _load();
  }

  Future<void> _open(Recording recording) async {
    final ok = await ScreenRecorder.openRecording(recording.uri);
    if (!mounted || ok) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('No hay ninguna app instalada que pueda abrir el video')),
    );
  }

  Future<void> _share(Recording recording) async {
    final ok = await ScreenRecorder.shareRecording(recording.uri);
    if (!mounted || ok) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('No hay ninguna app instalada para compartir el video')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final recordings = _recordings;
    return Scaffold(
      appBar: AppBar(title: const Text('Mis grabaciones')),
      body: _loadFailed
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('No se pudieron cargar las grabaciones'),
                  const SizedBox(height: 8),
                  TextButton(onPressed: _load, child: const Text('Reintentar')),
                ],
              ),
            )
          : recordings == null
          ? const Center(child: CircularProgressIndicator())
          : recordings.isEmpty
              ? const Center(child: Text('Todavía no hay grabaciones'))
              : ListView.builder(
                  itemCount: recordings.length,
                  itemBuilder: (context, index) {
                    final recording = recordings[index];
                    return ListTile(
                      leading: const Icon(Icons.videocam),
                      title: Text(recording.formattedDate),
                      subtitle: Text('${recording.formattedDuration} · ${recording.formattedSize}'),
                      onTap: () => _open(recording),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.share),
                            tooltip: 'Compartir',
                            onPressed: () => _share(recording),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline),
                            tooltip: 'Borrar',
                            onPressed: () => _delete(recording),
                          ),
                        ],
                      ),
                    );
                  },
                ),
    );
  }
}
